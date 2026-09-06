<script lang="ts" context="module">
  export type Params = {
    periodStart: number
    periodEnd: number
    currency: string
  }
</script>
<script lang="ts">
import Button from '../common/_button.svelte'
import ErrorPanel from '../common/form/_error_panel.svelte'
import {ValidationError} from '../common/form'
import {
  CURRENT_CURRENCY,
  FIRST_ACCOUNTING_PERIOD,
  formatAccountingPeriod,
  LATEST_ACCOUNTING_PERIOD
} from '../common/globals'

export let onSubmitted: (params: Params) => Promise<void>

let modal: HTMLDialogElement;

let isLoading = false
let errors: string[] = []

let periodStart: number = LATEST_ACCOUNTING_PERIOD
let periodEnd: number = LATEST_ACCOUNTING_PERIOD

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

  modal.showModal()
}

export function close(force: boolean = false): void {
  if (isLoading && !force) { return }
  modal.close()
}

async function submit(): Promise<void> {
  isLoading = true;

  try {
    await onSubmitted({
      periodStart,
      periodEnd,
      currency: CURRENT_CURRENCY,
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
        <select data-test-id="periodStart" class="select select-sm" bind:value={periodStart}>
          {#each validPeriods as period (period)}
            <option value={period}>{formatAccountingPeriod(period)}</option>
          {/each}
        </select>
      </div>
      <div>to</div>
      <div>
        <select data-test-id="periodEnd" class="select select-sm" bind:value={periodEnd}>
          {#each validPeriods as period (period)}
            <option value={period}>{formatAccountingPeriod(period)}</option>
          {/each}
        </select>
      </div>
    </div>
    <ErrorPanel {errors} />
    <div>
      <Button class="btn btn-sm btn-primary" dataTestId="submitButton" {isLoading} onClick={() => {void submit()}}>Apply</Button>
    </div>
  </div>
  <div class="modal-backdrop" onclick={() => close(false)}>
  </div>
</dialog>
