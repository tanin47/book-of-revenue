<script lang="ts">
import Layout from '../_layout.svelte'
import {post} from "../../common/form";
import Table from '../../common/_table.svelte'
import {
  type ColumnProperty,
  type FetchResult,
  makeSortParam,
  parseSortParam,
  type Sort,
  type TableParams
} from "../../common/table_models";
import {
  addMonths,
  CURRENT_CURRENCY, CURRENT_STRIPE_ACCOUNT,
  FIRST_ACCOUNTING_PERIOD,
  formatAccountingPeriod,
  LATEST_ACCOUNTING_PERIOD
} from "../../common/globals";
import {onMount} from "svelte";
import type {GlobalParams} from "./common";
import TopBar, {type Page} from "./_top_bar.svelte";

export let fetchUrl: string;
export let exportCsvUrl: string;
export let page: Page;
export let extraColumns: ColumnProperty[] = []

const COLUMN_PROPERTIES: ColumnProperty[] = [
  {id: 'CustomerId', name: 'Customer ID'},
  {id: 'CustomerName', name: 'Customer name'},
  {id: 'CustomerEmail', name: 'Customer email'},
  ...extraColumns
]

let totalNumberOfRows = 0
let highlightColumnId: string | null = null

interface Params extends TableParams, GlobalParams {}

let params: Params = {
  keyword: '',
  periodStart: Math.max(addMonths(LATEST_ACCOUNTING_PERIOD, -11), FIRST_ACCOUNTING_PERIOD),
  periodEnd: LATEST_ACCOUNTING_PERIOD,
  currency: CURRENT_CURRENCY,
  columns: [],
  sorts: [] as Sort[]
}

function generateQueryString(onlyForCustomerLink: boolean): string {
  const q = new URLSearchParams();

  q.append('start', formatAccountingPeriod(params.periodStart))
  q.append('end', formatAccountingPeriod(params.periodEnd))
  if (highlightColumnId) {
    q.append('h', highlightColumnId)
  }

  if (!onlyForCustomerLink) {
    if (params.keyword) {
      q.append('q', params.keyword)
    }
    if (params.sorts.length > 0) {
      q.append('sort', makeSortParam(params.sorts))
    }
  }

  return q.toString()
}

function updateParamsFromQueryString() {
  const queryString = window.location.search;
  const q = new URLSearchParams(queryString ?? '');

  try {
    params.keyword = q.get('q') ?? ''

    const startParam = q.get('start')
    params.periodStart = startParam ? new Date(startParam).getTime() : params.periodStart
    const endParam = q.get('end')
    params.periodEnd = endParam ? new Date(endParam).getTime() : params.periodEnd

    highlightColumnId = q.get('h') ?? null

    params.sorts = parseSortParam(q.get('sort'))
  } catch (e) {
    console.error('Error parsing query string: ', e, ' queryString: ', queryString, ' q: ', q, '')
  }
  params = params
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

async function load(params: Params, pushHistory: boolean): Promise<FetchResult> {
  if (pushHistory) {
    const queryString = generateQueryString(false)
    history.pushState({queryString}, '', `${window.location.origin}${window.location.pathname}?${queryString}`)
  }

  return fetch(params, 0)
}

async function fetch(params: Params, offset: number): Promise<FetchResult> {
  return post(fetchUrl, {params, offset})
}

async function exportCsv(): Promise<void> {
  const json = await post(exportCsvUrl, {params})

  setTimeout(
    () => {
      window.location.href = json.url
    },
    100
  )
}


function getAuditUrl(productId: string | null, period: string): string | null {
  if (!productId) { return null }

  let base: string
  let only: string
  const q = new URLSearchParams();

  switch (page) {
    case 'net-revenue':
      base = 'net-revenue'
      q.set('only', 'NetRevenue')
      break
    case 'deferred-revenue':
      base = 'balance-sheet'
      q.set('only', 'EndingBalance')
      q.set('accounts', 'DeferredRevenue')
      break
    default:
      return null
  }

  q.set('start', period)
  q.set('end', period)
  q.set('group', 'LineItem')
  q.set('product', productId)

  return `/${base}?${q.toString()}`
}

let table: Table

</script>

<Layout>
  <div class="flex flex-col w-full h-full grow overflow-hidden">
    <div class="flex flex-col grow w-full items-stretch overflow-hidden relative">
      <div class="bg-base-300 text-primary border-base-content border-b px-3 py-2">
        <div class="flex items-center justify-between gap-4">
          <div class="flex items-center gap-2.5 min-w-0">
            <i class="ph-duotone ph-storefront text-4xl"></i>
            <h1 class="font-bold text-base leading-tight truncate">Products</h1>
          </div>
        </div>
      </div>
      <TopBar
        {totalNumberOfRows}
        bind:params={params}
        onChange={() => {void table.load(true)}}
        currentPage={page}
        onExport={exportCsv}
      />
      <Table
        bind:this={table}
        bind:params={params}
        bind:totalNumberOfRows={totalNumberOfRows}
        stickFirstNColumns={1}
        highlightedColumnId={highlightColumnId}
        columnProperties={COLUMN_PROPERTIES}
        columnRenderingSettings={{
          ProductId: {hidden: true},
          ProductName: {computeLink: (value, dataRow, dataColumnIndexById) => `https://dashboard.stripe.com/${CURRENT_STRIPE_ACCOUNT!.stripeAccount.id}/products/${dataRow[dataColumnIndexById.ProductId]}`},
          '\\d{4}-\\d{2}': {isRegex: true, computeLink: (value, data, dataIndexById, colId) => getAuditUrl(data[dataIndexById.ProductId], colId)}
        }}
        onFetch={load}
        onFetchMore={fetch}
      />
    </div>
  </div>
</Layout>

<style lang="scss">
</style>
