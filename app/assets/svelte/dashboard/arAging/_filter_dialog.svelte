<script lang="ts" context="module">
import {type ColumnSelectionItem, makeColumnGroupBys, type Sort, type TableParams} from "../../common/table_models";

export type Params = TableParams & {
    exclusiveUpUntil: number
    groupBy: string
    customerId: string | null
    columns: string[]
    sorts: Sort[]
  }

  export const BASE_COLUMNS: ColumnSelectionItem[] = [
    {id: 'Date', forceChecked: true, rank: 1},
    {id: 'NotDue', forceChecked: true, rank: 3},
    {id: 'Days30', forceChecked: true, rank: 4},
    {id: 'Days60', forceChecked: true, rank: 5},
    {id: 'Days90', forceChecked: true, rank: 6},
    {id: 'Days120', forceChecked: true, rank: 7},
    {id: 'Days120Plus', forceChecked: true, rank: 8},
    {id: 'Total', forceChecked: true, rank: 9},
  ]
  export const COLUMNS = makeColumnGroupBys(['Summary', 'Customer', 'Transaction'], BASE_COLUMNS)
</script>
<script lang="ts">
import Button from '../../common/_button.svelte'
import DateSelector from '../../common/_date_selector.svelte'
import ErrorPanel from '../../common/form/_error_panel.svelte'
import {ValidationError} from "../../common/form";
import {FIRST_ACCOUNTING_PERIOD, LATEST_ACCOUNTING_PERIOD} from "../../common/globals";
import {sanitizeSelectedColumns, sanitizeSorts} from "../../common/table_models";
    import { COLUMN_NAMES } from "../../common/_table.svelte";

export let onSubmitted: (params: Params) => Promise<void>

let modal: HTMLDialogElement;

let isLoading = false
let errors: string[] = []

let exclusiveUpUntil: number = Date.UTC(new Date().getUTCFullYear(), new Date().getUTCMonth(), new Date().getUTCDate());
let groupBy: string = 'Summary'
let customerId: string | null = null
let selectedColumns: string[] = []
let sorts: Sort[] = []

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

  exclusiveUpUntil = params.exclusiveUpUntil
  groupBy = params.groupBy
  customerId = params.customerId
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
      exclusiveUpUntil: exclusiveUpUntil,
      groupBy,
      customerId,
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
  <div class="modal-box !min-w-[480px] !w-auto !max-w-none flex flex-col gap-2 text-sm">
    <div class="flex gap-2 items-center">
      <span class="font-bold">Date:</span>
      <div>
        <DateSelector
          value={exclusiveUpUntil}
          min={FIRST_ACCOUNTING_PERIOD}
          max={Date.UTC(new Date().getUTCFullYear(), new Date().getUTCMonth(), new Date().getUTCDate())}
          onSelected={(value) => {
            if (value) {
              exclusiveUpUntil = value
            }
          }}
        />
      </div>
    </div>
    <div class="flex gap-2 items-center">
      <span class="font-bold">Group by:</span>
      <div>
        <select
          bind:value={groupBy}
          class="select select-sm appearance-none"
        >
          {#each COLUMNS as {groupBy, columns} (groupBy)}
            <option value={groupBy}>{groupBy ?? '-- None --'}</option>
          {/each}
        </select>
      </div>
    </div>
    {#if customerId}
      <div class="flex gap-2 items-center">
        <span class="font-bold">Advance filters:</span>
        <div class="flex gap-2 items-center">
          {#if customerId}
            <div class="flex items-center gap-1 rounded bg-accent text-white text-xs px-2 py-1">Customer: {customerId}</div>
          {/if}
        </div>
      </div>
    {/if}
    <div class="flex flex-col gap-2">
      <span class="font-bold">Columns:</span>
      <div class="bg-gray-50 border border-neutral py-1 px-2 max-h-[200px] overflow-auto">
        <table class="w-full text-sm">
          <tbody>
            {#each groupOf3(possibleColumns) as thisRow, index (index)}
              <tr>
                {#each thisRow as column (column.id)}
                  <td class="min-w-[170px] max-w-[170px] text-ellipsis overflow-hidden">
                    <label class="flex items-center gap-2 py-1 {column.forceChecked ? 'cursor-not-allowed' : 'cursor-pointer'} w-full overflow-hidden text-ellipsis whitespace-nowrap">
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
