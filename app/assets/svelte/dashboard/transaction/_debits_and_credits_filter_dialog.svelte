<script lang="ts" context="module">
import {
  type ColumnProperty,
  type ColumnSelectionItem,
  makeColumnGroupBys,
  type TableParams
} from "../../common/table_models";

  export type Params = TableParams & {
    groupBy: string | null
    lineItemId: string | null
    accounts: string[]
    columns: string[]
  }

  export const BASE_COLUMNS: ColumnSelectionItem[] = [
    {id: 'AccountingPeriod', forceChecked: true, rank: 0},
    {id: 'Debit', forceChecked: true, rank: 1},
    {id: 'Credit', forceChecked: true, rank: 2},
    {id: 'Amount', forceChecked: true, rank: 3},
  ]

  export const COLUMNS = makeColumnGroupBys(['Summary', 'Product', 'LineItem', null], BASE_COLUMNS)
</script>
<script lang="ts">
import Button from '../../common/_button.svelte'
import ErrorPanel from '../../common/form/_error_panel.svelte'
import {post, ValidationError} from "../../common/form";
import {FIRST_ACCOUNTING_PERIOD, LATEST_ACCOUNTING_PERIOD} from "../../common/globals";
import {sanitizeSelectedColumns, sanitizeSorts, type Sort} from "../../common/table_models";
    import { COLUMN_NAMES } from "../../common/_table.svelte";
import type {InvoiceLineItem, RevRecTransaction} from "../../common/models";

export let transaction: RevRecTransaction
export let onSubmitted: (params: Params) => Promise<void>

let modal: HTMLDialogElement;

let isLoading = false
let errors: string[] = []

let groupBy: string | null = null
let accounts: string[] = []
let lineItemId: string | null = null
let selectedColumns: string[] = []
let sorts: Sort[] = []

let lineItems: InvoiceLineItem[] = []
let availableAccounts: string[] = []

$: {
  void fetchAllAccounts()
  void fetchLineItems()
}

async function fetchAllAccounts(): Promise<void> {
  const json = await post('/load-accounts', {})
  availableAccounts = json.accounts
}

async function fetchLineItems(): Promise<void> {
  try {
    const json = await post(`/load-line-items`, {transactionId: transaction.id})
    lineItems = json.lineItems
  } catch (e) {
    console.error('Error fetching line items: ', e)
  }
}


let possibleColumns: ColumnSelectionItem[] = []
$: {
  possibleColumns = COLUMNS.find(c => c.groupBy === groupBy)?.columns ?? []

  possibleColumns.forEach(c => {
    if (c.forceChecked && !selectedColumns.includes(c.id)) {
      selectedColumns.push(c.id)
    }
  })
}

export function open(params: Params): void {
  isLoading = false
  errors = []

  groupBy = params.groupBy
  lineItemId = params.lineItemId
  accounts = params.accounts
  selectedColumns = params.columns
  sorts = params.sorts

  modal.showModal()
}

export function close(force: boolean = false): void {
  if (isLoading && !force) { return }
  modal.close()
}

function groupOf3<T>(items: T[]): T[][] {
  const result: T[][] = []
  for (let i = 0; i < items.length; i += 3) {
    result.push(items.slice(i, i + 3))
  }
  return result
}

async function submit(): Promise<void> {
  isLoading = true;
  const possibleColumns = COLUMNS.find(c => c.groupBy === groupBy)!.columns

  try {
    await onSubmitted({
      groupBy,
      lineItemId,
      accounts,
      columns: sanitizeSelectedColumns(selectedColumns, possibleColumns),
      sorts: sanitizeSorts(sorts, possibleColumns)
    })
  } catch (e) {
    if (e instanceof ValidationError) {
      errors = e.messages
    } else {
      errors = ['An unknown error occurred. Please contact your administrator.']
    }
    isLoading = false
  }

  close(true)
}

</script>

<dialog bind:this={modal} class="modal2">
  <div class="modal-box !min-w-[500px] !w-auto !max-w-none flex flex-col gap-2 text-sm">
    <div class="flex gap-2 items-center">
      <span class="font-bold">Group by:</span>
      <div>
        <select
          bind:value={groupBy}
          class="select select-sm"
        >
          {#each COLUMNS as {groupBy, columns} (groupBy ?? 'None')}
            <option value={groupBy}>{groupBy ?? 'None'}</option>
          {/each}
        </select>
      </div>
    </div>
    {#if lineItems.length > 0}
      <div class="flex gap-2 items-center">
        <span class="font-bold">Line item:</span>
        <div>
          <select
            bind:value={lineItemId}
            class="select select-sm"
          >
            <option value={null}>Show all line items</option>
            {#each lineItems as lineItem (lineItem.id)}
              <option value={lineItem.id}>{lineItem.description ?? 'No description'} ({lineItem.id})</option>
            {/each}
          </select>
        </div>
      </div>
    {/if}
    <div class="flex flex-col gap-2">
      <div class="flex gap-2 items-center">
        <span class="font-bold">Filter for accounts:</span>
        <div>
          <select
            class="select select-sm"
            onchange={(e) => {
              const target = e.target as HTMLSelectElement
              const selected = target.value
              if (selected) {
                accounts.push(selected)
                accounts = accounts
              }
              target.selectedIndex = 0
            }}
          >
            <option value={null}>Select account</option>
            {#each availableAccounts as account (account)}
              <option value={account} disabled={accounts.includes(account)}>{account}</option>
            {/each}
          </select>
        </div>
        {#if accounts.length === 0}
          <div class="text-xs text-gray-600">(all accounts are shown)</div>
        {/if}
      </div>
      {#if accounts.length > 0}
        <div class="flex gap-2 items-center flex-wrap">
          {#each accounts as account (account)}
            <div class="flex items-center gap-1 rounded bg-accent text-white text-xs px-2 py-1">
              {account}
              <i
                class="ph ph-x-circle cursor-pointer text-sm"
                onclick={() => {
                  accounts = accounts.filter(a => a !== account)
                }}
              ></i>
            </div>
          {/each}
        </div>
      {/if}
    </div>
    <div class="flex flex-col gap-2">
      <span class="font-bold">Columns:</span>
      <div class="bg-gray-50 border border-base-content rounded py-1 px-2 max-h-[150px] overflow-auto">
        <table class="w-full text-sm">
          <tbody>
            {#each groupOf3(possibleColumns) as thisRow, index (index)}
              <tr>
                {#each thisRow as column (column.id)}
                  <td class="min-w-[170px] max-w-[170px] text-ellipsis overflow-hidden">
                    <label class="flex items-center gap-2 px-1 py-1 {column.forceChecked ? 'cursor-not-allowed' : 'cursor-pointer'} w-full overflow-hidden text-ellipsis whitespace-nowrap">
                      <input
                        type="checkbox"
                        disabled={column.forceChecked}
                        bind:group={selectedColumns}
                        value={column.id}
                      />
                      <span class="text-xs text-ellipsis overflow-hidden">{COLUMN_NAMES[column.id]}</span>
                    </label>
                  </td>
                {/each}
              </tr>
            {/each}
          </tbody>
        </table>
      </div>
    </div>
    <ErrorPanel {errors} />
    <div>
      <Button class="btn btn-sm btn-primary" {isLoading} onClick={() => {void submit()}}>Apply</Button>
    </div>
  </div>
  <div class="modal-backdrop" onclick={() => close(false)}>
  </div>
</dialog>
