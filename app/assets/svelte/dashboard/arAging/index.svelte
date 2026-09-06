<script lang="ts">
import Layout from '../_layout.svelte'
import FilterDialog, {BASE_COLUMNS, COLUMNS, type Params} from "./_filter_dialog.svelte";
import Table, { DEFAULT_COLUMN_RENDERING_SETTINGS } from "../../common/_table.svelte";
import {
  type FetchResult,
  makeSortParam,
  parseColumnQueryParam,
  parseSortParam,
  type Sort
} from "../../common/table_models";
import {post} from "../../common/form";
import {CURRENT_CURRENCY, formatDate, formatNumber, LATEST_ACCOUNTING_PERIOD} from "../../common/globals";
import {onMount} from "svelte";
import Button from "../../common/_button.svelte";

let totalNumberOfRows = 0

let params: Params = {
  exclusiveUpUntil: Date.UTC(new Date().getUTCFullYear(), new Date().getUTCMonth(), new Date().getUTCDate()),
  groupBy: 'Summary',
  customerId: null,
  columns: BASE_COLUMNS.map((column) => column.id),
  sorts: []
}

function generateQueryString(
  overrides: {
    groupBy?: string | null
    customerId?: string | null
    sorts?: Sort[] | null
  } | null = null
): string {
  const q = new URLSearchParams();

  q.append('until', new Date(params.exclusiveUpUntil).toISOString().substring(0, 10));
  q.append('group', overrides?.groupBy ?? params.groupBy);

  const customerId = overrides?.customerId ?? params.customerId;
  if (customerId) { q.append('customer', customerId); }

  if (params.columns.length > 0) {
    q.append('columns', params.columns.join(','));
  }

  const sorts = overrides?.sorts ?? params.sorts;
  if (sorts.length > 0) {
    q.append('sort', makeSortParam(sorts))
  }

  return q.toString()
}

function updateParamsFromQueryString() {
  const queryString = window.location.search;
  const q = new URLSearchParams(queryString ?? '');

  try {
    const until = q.get('until')
    params.exclusiveUpUntil = until ? new Date(until).getTime() : LATEST_ACCOUNTING_PERIOD
    console.log(`Read from param: ${params.exclusiveUpUntil}`)
    params.groupBy = q.get('group') ?? params.groupBy
    params.customerId = q.get('customer') ?? params.customerId
    params.columns = parseColumnQueryParam(q.get('columns'), params.groupBy, COLUMNS)

    params.sorts = parseSortParam(q.get('sort'))
  } catch (e) {
    console.error('Error parsing query string: ', e, ' queryString: ', queryString, ' q: ', q, '')
  }
  params = params
}

async function load(params: Params, pushHistory: boolean): Promise<FetchResult> {
  if (pushHistory) {
    const queryString = generateQueryString()
    history.pushState({queryString}, '', `${window.location.origin}${window.location.pathname}?${queryString}`)
  }

  return fetch(params, 0)
}

function makeParams(params: Params): Record<string, any> {
  return {
    ...params,
    exclusiveUpUntil: params.exclusiveUpUntil + 86400000,
    currency: CURRENT_CURRENCY
  }
}

async function fetch(params: Params, offset: number): Promise<FetchResult> {
  return post(`/load-ar-aging`, {
    params: makeParams(params),
    offset
  })
}

onMount(() => {
  function loadPage(pushHistory: boolean) {
    updateParamsFromQueryString()
    void table.load(pushHistory)
  }

  function popstate() {
    loadPage(false)
  }

  window.addEventListener('popstate', popstate)
  loadPage(false)

  return () => {
    window.removeEventListener('popstate', popstate)
  }
})

let isExporting = false
async function exportCsv(): Promise<void> {
  isExporting = true;

  try {
    const json = await post(`/export-ar-aging`, {
      params: makeParams(params)
    })

    setTimeout(
      () => {
        window.location.href = json.url
      },
      100
    )
  } catch (e) {
    // if (e instanceof ValidationError) {
    //   errors = e.messages
    // } else {
    //   errors = ['An unknown error occurred. Please contact your administrator.']
    // }
  } finally {
    isExporting = false;
  }
}

function computeLink(
  value: any,
  dataRow: any[],
  dataColumnIndexById: {[key: string]: number},
  columnId: string,
  params: any
): string | null {
  const sorts = [{columnId, direction: 'Desc'}] as Sort[]
  switch (params.groupBy) {
    case 'Summary':
      return `/ar-aging?${generateQueryString({groupBy: 'Customer', sorts})}`
    case 'Customer':
      return `/ar-aging?${generateQueryString({groupBy: 'Transaction', customerId: dataRow[dataColumnIndexById.CustomerId], sorts})}`
    default:
      return null
  }
}

let table: Table
let filterDialog: FilterDialog
</script>


<FilterDialog
  bind:this={filterDialog}
  onSubmitted={async (newParams) => {
    params = newParams
    void table.load(true)
  }}
/>

<Layout>
  <div class="flex flex-col w-full h-full grow overflow-hidden">
    <div class="flex flex-col grow w-full items-stretch overflow-hidden relative">
      <div class="flex justify-between gap-2 items-stretch bg-base-200 text-sm text-primary border-base-content border-b">
        <div class="flex items-stretch gap-0">
          <span class="font-bold py-1 border-e border-base-content px-2 flex items-center">Total: {formatNumber(totalNumberOfRows)}</span>
          <div class="text-xs px-2 py-1 flex items-center gap-2">
            <Button class="btn btn-xs btn-secondary shadow-none" onClick={() => {filterDialog.open(params)}}>Filter</Button>
            <div class="flex items-center gap-1">
              <span class="font-bold">Date:</span>
              <span data-test-id="date">{formatDate(params.exclusiveUpUntil)} (end of day)</span>
            </div>
            <div class="flex items-center gap-1">
              <span class="font-bold">Group by:</span>
              <span>{params.groupBy}</span>
            </div>
            {#if params.customerId}
              <div class="flex items-center gap-1">
                <span class="font-bold">Customer:</span>
                <span>{params.customerId}</span>
              </div>
            {/if}
          </div>
        </div>
        <div class="flex items-stretch justify-stretch gap-0">
          <div class="px-2 py-1 flex items-center">
            <Button class="btn btn-xs btn-info shadow-none" isLoading={isExporting} onClick={() => {void exportCsv()}}>Export</Button>
          </div>
        </div>
      </div>
      <Table
        bind:this={table}
        bind:params={params}
        bind:totalNumberOfRows={totalNumberOfRows}
        columnRenderingSettings={{
          ...DEFAULT_COLUMN_RENDERING_SETTINGS,
          'NotDue': {computeLink},
          'Days30': {computeLink},
          'Days60': {computeLink},
          'Days90': {computeLink},
          'Days120': {computeLink},
          'Days120Plus': {computeLink},
          'Total': {computeLink}
        }}
        onFetch={load}
        onFetchMore={fetch}
      />
    </div>
  </div>
</Layout>


<style lang="scss">
</style>
