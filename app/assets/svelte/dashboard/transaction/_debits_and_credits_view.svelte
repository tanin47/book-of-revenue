<script lang="ts">
import type {RevRecTransaction} from "../../common/models";
import Table from "../../common/_table.svelte";
import {type FetchResult, makeSortParam, parseSortParam} from "../../common/table_models";
import {post} from "../../common/form";
import Button from "../../common/_button.svelte";
import {CURRENT_CURRENCY, formatNumber} from "../../common/globals";
import {onMount} from "svelte";
import FilterDialog, {BASE_COLUMNS, type Params} from "./_debits_and_credits_filter_dialog.svelte";

export let transaction: RevRecTransaction
export let onSwitchToAccountSummary: () => void

let totalNumberOfRows = 0
let params: Params = {
  groupBy: 'Summary',
  lineItemId: null,
  accounts: [],
  columns: BASE_COLUMNS.map((column) => column.id),
  sorts: []
}
let table: Table

function generateQueryString(): string {
  const q = new URLSearchParams();

  q.append('view', 'debits-and-credits')

  q.append('group', params.groupBy ?? 'None');

  if (params.lineItemId) {
    q.append('il', params.lineItemId);
  }

  if (params.accounts.length > 0) {
    q.append('accounts', params.accounts.join(','));
  }

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
    params.groupBy = q.get('group') ?? params.groupBy
    if (params.groupBy === 'None') { params.groupBy = null }

    params.lineItemId = q.get('il') ?? null

    params.accounts = (q.get('accounts') ?? '').split(',').map(a => a.trim()).filter((a) => a.length > 0)
    params.columns = (q.get('columns') ?? '').split(',').map(a => a.trim()).filter((a) => a.length > 0)
    BASE_COLUMNS.forEach((column) => {
      if (!params.columns.includes(column.id)) {
        params.columns.push(column.id)
      }
    })
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


export async function refresh(): Promise<void> {
  await table.load(false)
}

async function fetch(params: Params, offset: number): Promise<FetchResult> {
  return post(`/load-debits-and-credits`, {
    params: {...params, transactionId: transaction.id, currency: CURRENT_CURRENCY},
    offset
  })
}

async function load(params: Params, pushHistory: boolean): Promise<FetchResult> {
  if (pushHistory) {
    const queryString = generateQueryString()
    history.pushState({queryString}, '', `${window.location.origin}${window.location.pathname}?${queryString}`)
  }

  return fetch(params, 0)
}

let isExporting = false
async function exportCsv(): Promise<void> {
  isExporting = true;

  try {
    const json = await post(`/export-debits-and-credits`, {
      params: {...params, transactionId: transaction.id, currency: CURRENT_CURRENCY},
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
    isExporting = false
  }
}

let filterDialog: FilterDialog
</script>

<FilterDialog
  bind:this={filterDialog}
  transaction={transaction}
  onSubmitted={async (newParams) => {
    params = newParams
    void table.load(true)
  }}
/>

<div class="flex justify-between gap-2 items-stretch bg-base-200 text-sm text-primary border-base-content border-b">
  <div class="flex items-stretch gap-0">
    <span class="font-bold py-1 border-e border-base-content px-2 flex items-center whitespace-nowrap">Total: {formatNumber(totalNumberOfRows)}</span>
    <div class="text-xs px-2 py-1 flex items-center gap-2">
      <Button class="btn btn-xs btn-secondary shadow-none" onClick={() => {filterDialog.open(params)}}>Filter</Button>
      {#if params.groupBy}
        <div class="flex items-center gap-1">
          <span class="font-bold">Group by:</span>
          <span>{params.groupBy}</span>
        </div>
      {/if}
      {#if params.lineItemId}
        <div class="flex items-center gap-1">
          <span class="font-bold">Line item:</span>
          <span>{params.lineItemId}</span>
        </div>
      {/if}
      {#if params.accounts.length > 0}
        <div class="flex items-center gap-1">
          <span class="font-bold">Only show:</span>
          <span>{params.accounts.join(', ')}</span>
        </div>
      {/if}
    </div>
  </div>
  <div class="flex items-center justify-stretch gap-0">
    <div class="px-2 py-2 flex items-center gap-2">
      <Button class="btn btn-xs btn-info shadow-none" isLoading={isExporting} onClick={() => {void exportCsv()}}>Export</Button>
    </div>
    <div class="pe-2">
      <span class="text-xs font-bold cursor-pointer link" onclick={() => {onSwitchToAccountSummary()}}>Switch to Account Summary</span>
    </div>
  </div>
</div>
<Table
  bind:this={table}
  bind:params={params}
  bind:totalNumberOfRows={totalNumberOfRows}
  onFetch={load}
  onFetchMore={fetch}
/>
