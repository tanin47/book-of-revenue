<script lang="ts" context="module">
import {
  type ColumnProperty,
  type ColumnSelectionItem,
  makeColumnGroupBys,
  type TableParams
} from "../../common/table_models";

  export type Params = TableParams & {
    periodStart: number
    periodEnd: number
    groupBy: string
    accounts: string[]
    columns: string[]
  }

  export const BASE_COLUMNS: ColumnSelectionItem[] = [
    {id: 'AccountingPeriod', forceChecked: true, rank: 0},
    {id: 'Debit', forceChecked: true, rank: 1},
    {id: 'Credit', forceChecked: true, rank: 2},
    {id: 'Amount', forceChecked: true, rank: 3},
  ]

  const COLUMNS = makeColumnGroupBys(['Summary', 'Product', 'Customer', 'Transaction', 'LineItem'], BASE_COLUMNS)
</script>
<script lang="ts">
import Button from '../../common/_button.svelte'
import ErrorPanel from '../../common/form/_error_panel.svelte'
import {post, ValidationError} from "../../common/form";
import {FIRST_ACCOUNTING_PERIOD, LATEST_ACCOUNTING_PERIOD} from "../../common/globals";
import {sanitizeSelectedColumns, sanitizeSorts, type Sort} from "../../common/table_models";
    import { COLUMN_NAMES } from "../../common/_table.svelte";

export let onSubmitted: (params: Params) => Promise<void>

let modal: HTMLDialogElement;

let isLoading = false
let errors: string[] = []

let periodStart: number = LATEST_ACCOUNTING_PERIOD
let periodEnd: number = LATEST_ACCOUNTING_PERIOD
let groupBy: string = 'Summary'
let accounts: string[] = []
let selectedColumns: string[] = []
let sorts: Sort[] = []

let availableAccounts: string[] = []

$: {void fetchAllAccounts()}

async function fetchAllAccounts(): Promise<void> {
  const json = await post('/load-accounts', {})
  availableAccounts = json.accounts
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

const validPeriods: number[] = []
$: {
  let current = FIRST_ACCOUNTING_PERIOD
  while (current <= LATEST_ACCOUNTING_PERIOD) {
    validPeriods.push(current)
    current = Date.UTC(new Date(current).getUTCFullYear(), new Date(current).getUTCMonth() + 1, 1)
  }
}

export function open(params: Params): void {
  isLoading = false
  errors = []

  periodStart = params.periodStart
  periodEnd = params.periodEnd
  groupBy = params.groupBy
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
      periodStart,
      periodEnd,
      groupBy,
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
      <span class="font-bold">Period:</span>
      <div>
        <select class="select select-sm" bind:value={periodStart}>
          {#each validPeriods as period (period)}
            <option value={period}>{new Date(period).toLocaleDateString('en-US', {month: 'short', year: 'numeric', timeZone: 'UTC'})}</option>
          {/each}
        </select>
      </div>
      <div>to</div>
      <div>
        <select class="select select-sm" bind:value={periodEnd}>
          {#each validPeriods as period (period)}
            <option value={period}>{new Date(period).toLocaleDateString('en-US', {month: 'short', year: 'numeric', timeZone: 'UTC'})}</option>
          {/each}
        </select>
      </div>
    </div>
    <div class="flex gap-2 items-center">
      <span class="font-bold">Group by:</span>
      <div>
        <select
          bind:value={groupBy}
          class="select select-sm"
        >
          {#each COLUMNS as {groupBy, columns} (groupBy)}
            <option value={groupBy}>{groupBy}</option>
          {/each}
        </select>
      </div>
    </div>
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
