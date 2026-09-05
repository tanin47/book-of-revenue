<script lang="ts">
import Layout from '../_layout.svelte'
import {post} from "../../common/form";
import Table, { DEFAULT_COLUMN_RENDERING_SETTINGS } from '../../common/_table.svelte'
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
  CURRENT_CURRENCY,
  FIRST_ACCOUNTING_PERIOD,
  formatAccountingPeriod,
  LATEST_ACCOUNTING_PERIOD
} from "../../common/globals";
import {onMount} from "svelte";
import type {GlobalParams} from "./common";
import TopBar, {type Page} from "./_top_bar.svelte";
import type {Customer} from "../../common/models";

export let customerId: string | null
export let customer: Customer | null
export let fetchUrl: string;
export let exportCsvUrl: string;
export let page: Page;
export let extraColumns: ColumnProperty[] = []

const COLUMN_PROPERTIES: ColumnProperty[] = [
  {id: 'RevRecTransactionId', name: 'Transaction ID'},
  {id: 'RevRecTransactionTitle', name: 'Transaction'},
  {id: 'TransactionValue', name: 'Value'},
  {id: 'RevRecTransactionType', name: 'Type'},
  {id: 'TransactionStatus', name: 'Status'},
  {id: 'TransactionDate', name: 'Date'},
  ...extraColumns
]

let totalNumberOfRows = 0

interface Params extends TableParams, GlobalParams {}

let params: Params = {
  keyword: '',
  periodStart: Math.max(addMonths(LATEST_ACCOUNTING_PERIOD, -11), FIRST_ACCOUNTING_PERIOD),
  periodEnd: LATEST_ACCOUNTING_PERIOD,
  currency: CURRENT_CURRENCY,
  columns: [],
  sorts: [] as Sort[]
}
let highlightColumnId: string | null = null

function generateQueryString(): string {
  const q = new URLSearchParams();

  if (params.keyword) {
    q.append('q', params.keyword)
  }

  q.append('start', formatAccountingPeriod(params.periodStart))
  q.append('end', formatAccountingPeriod(params.periodEnd))
  if (highlightColumnId) {
    q.append('h', highlightColumnId)
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
    const queryString = generateQueryString()
    history.pushState({queryString}, '', `${window.location.origin}${window.location.pathname}?${queryString}`)
  }

  return fetch(params, 0)
}

async function fetch(params: Params, offset: number): Promise<FetchResult> {
  return post(fetchUrl, {
    params: {
      ...params,
      customerId
    },
    offset
  })
}

async function exportCsv(): Promise<void> {
  const json = await post(exportCsvUrl, {
    params: {
      ...params,
      customerId
    }
  })

  setTimeout(
    () => {
      window.location.href = json.url
    },
    100
  )
}

let table: Table

</script>

<Layout>
  <div class="flex flex-col w-full h-full grow overflow-hidden">
    <div class="flex flex-col grow w-full items-stretch overflow-hidden relative">
      <div class="bg-base-300 text-primary border-base-content border-b px-3 py-2 flex flex-col gap-3">
        <div class="text-xs flex gap-1 items-center">
          <a href="/customer" class="link">Customers</a>
          <span>&gt;</span>
          <a href="/customer/{customerId ?? 'empty'}" class="link">{customer?.name ?? customer?.email ?? customerId ?? '[empty]'}</a>
        </div>
        <div class="flex items-center justify-between gap-4">
          <div class="flex items-center gap-2.5 min-w-0">
            <i class="ph-duotone ph-user text-4xl"></i>
            <div class="flex flex-col gap-0.5">
              <h1 class="font-bold text-base leading-tight truncate" class:italic={!customer?.name}>{customer?.name ?? '[empty]'}</h1>
              <div class="text-xs opacity-70 leading-tight" class:italic={!customer?.email}>
                {#if customer?.email}
                  <a href="mailto:{customer.email}" class="truncate hover:underline">{customer.email}</a>
                {:else}
                  No email
                {/if}
              </div>
            </div>
          </div>
          <div class="flex items-center gap-1.5 shrink-0">
            {#if customerId}
              <a href="https://dashboard.stripe.com/customers/{customerId}" target="_blank" class="btn btn-xs btn-info shadow-none">View on Stripe</a>
            {/if}
          </div>
        </div>
      </div>
      <TopBar
        {customerId}
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
        bind:highlightedColumnId={highlightColumnId}
        stickFirstNColumns={1}
        columnProperties={COLUMN_PROPERTIES}
        columnRenderingSettings={{
          ...DEFAULT_COLUMN_RENDERING_SETTINGS,
          RevRecTransactionId: {primaryKey: true, hidden: true},
        }}
        onFetch={load}
        onFetchMore={fetch}
      />
    </div>
  </div>
</Layout>

<style lang="scss">
</style>
