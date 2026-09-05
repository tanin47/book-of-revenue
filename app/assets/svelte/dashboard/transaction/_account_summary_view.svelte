<script lang="ts">
import Table from "../../common/_table.svelte";
import {formatAccountingPeriod, LATEST_ACCOUNTING_PERIOD} from "../../common/globals";
import {
  type ColumnProperty,
  type FetchResult,
  makeSortParam,
  parseSortParam,
  type TableParams
} from "../../common/table_models";
import {post} from "../../common/form";
import type {InvoiceLineItem, RevRecTransaction} from "../../common/models";
import {onMount} from "svelte";

export let transaction: RevRecTransaction
export let onSwitchToDebitsCredits: () => void

const COLUMN_PROPERTIES: ColumnProperty[] = [
  {id: 'Account', name: 'Account'},
  {id: 'Ending', name: 'Ending', highlighted: true},
  {id: 'Remaining', name: 'Remaining'},
]
interface Params extends TableParams {
  transactionId: string
  lineItemId: string | null
}

let params: Params = {
  transactionId: transaction.id,
  lineItemId: null,
  columns: [],
  sorts: []
}

let table: Table
let totalNumberOfRows = 0

let entries: any[] | null = null
let periods: number[] = []
let endingPeriod: number | null = LATEST_ACCOUNTING_PERIOD

let lineItems: InvoiceLineItem[] = []

async function fetchLineItems(): Promise<void> {
  try {
    const json = await post(`/load-line-items`, {transactionId: params.transactionId})
    lineItems = json.lineItems
  } catch (e) {
    console.error('Error fetching line items: ', e)
  }
}

async function fetch(params: Params): Promise<FetchResult> {
  const json = await post(`/load-account-summary`, params)
  entries = json.entries

  const periodSets = new Set<number>()
  const accountSets = new Set<string>()
  const values = new Map<string, number>()
  const makeKey = (period: number, account: string) => `${account}.${period}`
  for (const entry of entries!) {
    periodSets.add(entry.accountingPeriod)
    accountSets.add(entry.account)
    values.set(makeKey(entry.accountingPeriod, entry.account), entry.settlementAmount)
  }

  const accounts = Array.from(accountSets).sort((a, b) => a.localeCompare(b))
  periods = Array.from(periodSets).sort((a, b) => a - b)
  const periodColumns = periods.filter(p => endingPeriod === null || p <= endingPeriod).map(period => ({id: formatAccountingPeriod(period), type: 'DeltaAmount'}))

  const fetchedResult = {
    totalNumberOfRows: accounts.length,
    columns: [
      {id: 'Account', type: 'String'},
      ...periodColumns,
      {id: 'Ending', type: 'Amount'},
      {id: 'Remaining', type: 'DeltaAmount'},
    ],
    rows: accounts.map(account => {
      let remaining = 0
      let ending = 0
      periods.filter(p => endingPeriod !== null && p > endingPeriod).forEach(p => remaining += (values.get(makeKey(p, account)) ?? 0))
      periods.filter(p => endingPeriod === null || p <= endingPeriod).forEach(p => ending += (values.get(makeKey(p, account)) ?? 0))
      return [
        account,
        ...periods.filter(p => endingPeriod === null || p <= endingPeriod).map(period => {
          return values.get(makeKey(period, account)) ?? 0
        }),
        ending,
        remaining
      ] as any[]
    })
  }

  params.sorts.reverse().forEach(sort => {
    const colIndex = fetchedResult.columns.findIndex(c => c.id === sort.columnId)
    if (colIndex < 0) { return; }

    const column = fetchedResult.columns[colIndex]
    fetchedResult.rows.sort((a, b) => {
      let diff = 0
      switch (column.type) {
        case 'Period':
        case 'Number':
        case 'Amount':
        case 'Date':
        case 'Timestamp':
          diff = (a[colIndex] ?? 0) - (b[colIndex] ?? 0)
          break;
        case 'String':
          diff = a[colIndex].localeCompare(b[colIndex])
      }

      return sort.direction === 'Asc' ? diff : -diff
    })
  })

  return fetchedResult
}

async function load(params: Params, pushHistory: boolean): Promise<FetchResult> {
  if (pushHistory) {
    const queryString = generateQueryString()
    history.pushState({queryString}, '', `${window.location.origin}${window.location.pathname}?${queryString}`)
  }

  return fetch(params)
}

export async function refresh() {
  await table.load(false)
}

onMount(() => {
  void fetchLineItems()

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

function generateQueryString(): string {
  const q = new URLSearchParams();

  q.append('view', 'account-summary')

  if (endingPeriod != null) {
    q.append('end', new Date(endingPeriod).toISOString().substring(0, 7));
  }

  if (params.lineItemId != null) {
    q.append('il', params.lineItemId);
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
    const endParam = q.get('end')
    endingPeriod = endParam ? new Date(endParam).getTime() : null

    const ilParam = q.get('il')
    if (ilParam) {
      params.lineItemId = ilParam
    }

    params.sorts = parseSortParam(q.get('sort'))
  } catch (e) {
    console.error('Error parsing query string: ', e, ' queryString: ', queryString, ' q: ', q, '')
  }
  params = params
}

</script>

<div class="flex justify-between gap-2 items-stretch bg-base-200 text-xs text-primary border-base-content border-b">
  <div class="flex items-stretch gap-0">
    <div class="flex gap-2 items-center px-2 py-1">
      {#if lineItems.length > 0}
        <div class="w-fit">
          <select
            bind:value={params.lineItemId}
            class="select select-xs bg-base-200 max-w-[200px]"
            onchange={() => {void table.load(true)}}
          >
            <option value={null}>Show all line items</option>
            {#each lineItems as lineItem (lineItem.id)}
              <option value={lineItem.id}>{lineItem.description ?? 'No description'} ({lineItem.id})</option>
            {/each}
          </select>
        </div>
      {/if}
      <span class="font-bold">Ending:</span>
      <div>
        <select
          bind:value={endingPeriod}
          class="select select-xs bg-base-200"
          onchange={() => {void table.load(true)}}
        >
          {#each periods ?? [] as period (period)}
            <option value={period}>{formatAccountingPeriod(period)}</option>
          {/each}
          <option value={null}>None</option>
        </select>
      </div>
    </div>
  </div>
  <div class="flex items-center px-2">
    <div>
      <span class="text-xs font-bold cursor-pointer link" onclick={() => {onSwitchToDebitsCredits()}}>Switch to Debits & Credits</span>
    </div>
  </div>
</div>
<Table
  bind:this={table}
  bind:params={params}
  bind:totalNumberOfRows={totalNumberOfRows}
  stickFirstNColumns={1}
  columnProperties={COLUMN_PROPERTIES}
  columnRenderingSettings={{}}
  onFetch={load}
  onFetchMore={async (params, offset) => {return {rows:[]}}}
/>
