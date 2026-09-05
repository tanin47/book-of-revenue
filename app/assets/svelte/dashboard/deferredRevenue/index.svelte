<script lang="ts">
import Layout from '../_layout.svelte'
import {post} from "../../common/form";
import Table, { DEFAULT_COLUMN_RENDERING_SETTINGS } from "../../common/_table.svelte";
import {type FetchResult, makeSortParam, parseColumnQueryParam, parseSortParam} from "../../common/table_models";
import {
  addMonths,
  CURRENT_CURRENCY,
  FIRST_ACCOUNTING_PERIOD, formatAccountingPeriod,
  formatNumber,
  LATEST_ACCOUNTING_PERIOD
} from "../../common/globals";
import FilterDialog, {BASE_COLUMNS, COLUMNS, type Params} from './_filter_dialog.svelte';
import Button from "../../common/_button.svelte";
import {onMount} from "svelte";

let totalNumberOfRows = 0

let params: Params = {
  periodStart: Math.max(addMonths(LATEST_ACCOUNTING_PERIOD, -11), FIRST_ACCOUNTING_PERIOD),
  periodEnd: LATEST_ACCOUNTING_PERIOD,
  groupBy: 'Summary',
  showOnly: null,
  productId: null,
  customerId: null,
  transactionId: null,
  columns: BASE_COLUMNS.map((column) => column.id),
  sorts: []
}

function generateQueryString(
  overrides: {
    period?: number | null,
    groupBy?: string | null,
    showOnly?: string | null,
    productId?: string | null,
    customerId?: string | null,
    transactionId?: string | null,
  } | null = null
): string {
  const q = new URLSearchParams();

  q.append('start', formatAccountingPeriod(overrides?.period ?? params.periodStart));
  q.append('end', formatAccountingPeriod(overrides?.period ?? params.periodEnd));
  q.append('group', overrides?.groupBy ?? params.groupBy);

  const only = overrides?.showOnly ?? params.showOnly;
  if (only) { q.append('only', only); }

  const productId = overrides?.productId ?? params.productId;
  if (productId) { q.append('product', productId); }
  const customerId = overrides?.customerId ?? params.customerId;
  if (customerId) { q.append('customer', customerId); }
  const transactionId = overrides?.transactionId ?? params.transactionId;
  if (transactionId) { q.append('transaction', transactionId); }

  if (params.columns.length > 0) {
    q.append('columns', params.columns.join(','));
  }

  if (params.sorts.length > 0) {
    q.append('sort', makeSortParam(params.sorts))
  }

  return q.toString()
}

function updateParamsFromQueryString() {
  const queryString = window.location.search;
  const q = new URLSearchParams(queryString ?? '');

  try {
    const startParam = q.get('start')
    params.periodStart = startParam ? new Date(startParam).getTime() : params.periodStart
    const endParam = q.get('end')
    params.periodEnd = endParam ? new Date(endParam).getTime() : params.periodEnd

    params.groupBy = q.get('group') ?? params.groupBy ?? 'Summary'
    params.showOnly = q.get('only') ?? params.showOnly ?? null
    params.productId = q.get('product') ?? params.productId ?? null
    params.customerId = q.get('customer') ?? params.customerId ?? null
    params.transactionId = q.get('transaction') ?? params.transactionId ?? null
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

async function fetch(params: Params, offset: number): Promise<FetchResult> {
  return post('/load-deferred-revenue', {
    params: {...params, currency: CURRENT_CURRENCY},
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

async function exportCsv(): Promise<void> {
  const json = await post('/export-deferred-revenue', {params})

  setTimeout(
    () => {
      window.location.href = json.url
    },
    100
  )
}

function computeLink(
  value: any,
  dataRow: any[],
  dataColumnIndexById: {[key: string]: number},
  columnId: string,
  params: any
): string | null {
  const opts = {period: dataRow[dataColumnIndexById.AccountingPeriod], showOnly: columnId}
  switch (params.groupBy) {
    case 'Summary':
      return `/deferred-revenue?${generateQueryString({...opts, groupBy: 'Customer'})}`
    case 'Product':
      return `/deferred-revenue?${generateQueryString({...opts, groupBy: 'LineItem', productId: dataRow[dataColumnIndexById.ProductId]})}`
    case 'Customer':
      return `/deferred-revenue?${generateQueryString({...opts, groupBy: 'Transaction', customerId: dataRow[dataColumnIndexById.CustomerId]})}`
    case 'Transaction':
      return `/deferred-revenue?${generateQueryString({...opts, groupBy: 'LineItem', transactionId: dataRow[dataColumnIndexById.RevRecTransactionId]})}`
    case 'LineItem':
      return null
    default:
      return null
  }
}

let filterDialog: FilterDialog
let table: Table
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
              <span class="font-bold">Period:</span>
              <span>{new Date(params.periodStart).toISOString().substring(0, 7)} to {new Date(params.periodEnd).toISOString().substring(0, 7)}</span>
            </div>
            <div class="flex items-center gap-1">
              <span class="font-bold">Group by:</span>
              <span>{params.groupBy}</span>
            </div>
            {#if params.showOnly}
              <div class="flex items-center gap-1">
                <span class="font-bold">Show only:</span>
                <span>{params.showOnly}</span>
              </div>
            {/if}
            {#if params.productId}
              <div class="flex items-center gap-1">
                <span class="font-bold">Product:</span>
                <span>{params.productId}</span>
              </div>
            {/if}
            {#if params.customerId}
              <div class="flex items-center gap-1">
                <span class="font-bold">Customer:</span>
                <span>{params.customerId}</span>
              </div>
            {/if}
            {#if params.transactionId}
              <div class="flex items-center gap-1">
                <span class="font-bold">Transaction:</span>
                <span>{params.transactionId}</span>
              </div>
            {/if}
          </div>
        </div>
        <div class="flex items-stretch justify-stretch gap-0">
          <div class="px-2 py-1 flex items-center">
            <Button class="btn btn-xs btn-info shadow-none" onClick={() => {void exportCsv()}}>Export</Button>
          </div>
        </div>
      </div>
      <Table
        bind:this={table}
        bind:params={params}
        bind:totalNumberOfRows={totalNumberOfRows}
        columnRenderingSettings={{
          ...DEFAULT_COLUMN_RENDERING_SETTINGS,
          'NetChange': {computeLink},
          '.+': {isRegex: true, computeLink},
        }}
        onFetch={load}
        onFetchMore={fetch}
      />
    </div>
  </div>
</Layout>


<style lang="scss">
</style>
