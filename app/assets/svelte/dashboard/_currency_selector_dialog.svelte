<script lang="ts">
import {post, ValidationError} from "../common/form";
import ErrorPanel from "../common/form/_error_panel.svelte";
import {onMount} from "svelte";
import {CURRENT_CURRENCY} from "../common/globals";

let errors: string[] = []

let currencies: string[] = []

async function load(): Promise<void> {
  try {
    const json = await post('/settings/load-currencies', {})

    currencies = json.currencies
  } catch (e) {
    if (e instanceof ValidationError) {
      errors = e.messages
    } else {
      errors = ['An unknown error occurred. Please contact your administrator.']
    }
  }
}

onMount(() => {void load()})

let modal: HTMLDialogElement;

export function open(): void {
  modal.showModal()
}

export function close(): void {
  modal.close()
}
</script>

<dialog bind:this={modal} class="modal2">
  <div class="modal-box !min-w-[440px] !w-auto !max-w-none flex flex-col gap-4 text-sm">
    <div class="flex items-center justify-between">
      <div class="flex flex-col">
        <span class="text-base font-bold">Switch currency</span>
        <span class="text-xs text-gray-600">Select a display currency.</span>
      </div>
      <button
        type="button"
        title="Close"
        class="btn btn-sm btn-ghost btn-circle"
        onclick={() => close()}
      >
        <i class="ph-bold ph-x text-base"></i>
      </button>
    </div>

    <div class="flex flex-col gap-2 max-h-[50vh] overflow-y-auto">
      {#each currencies as currency (currency)}
        {@const isActive = currency.toLowerCase() === CURRENT_CURRENCY?.toLowerCase()}
        <a
          href={`/settings/switch-currency/${currency}?redirect=${encodeURIComponent(window.location.href)}`}
          class="flex items-center gap-3 rounded-lg border px-3 py-2 transition-colors
            {isActive ? 'border-primary bg-primary/10' : 'border-gray-600 hover:bg-base-200'}"
        >
          <div class="flex items-center justify-center w-8 h-8 rounded-full bg-primary/10 text-primary shrink-0">
            <i class="ph-duotone ph-currency-circle-dollar text-lg"></i>
          </div>
          <span class="text-sm font-semibold uppercase flex-1 min-w-0 text-ellipsis whitespace-nowrap overflow-hidden">{currency}</span>
          {#if isActive}
            <i class="ph-bold ph-check text-base text-primary shrink-0"></i>
          {/if}
        </a>
      {/each}
    </div>

    <div class="flex items-center gap-2 text-xs text-gray-600">
      <i class="ph-bold ph-info shrink-0"></i>
      <span>The currencies are based on your connected banks in Stripe.</span>
    </div>
    <ErrorPanel {errors} />
  </div>
  <div class="modal-backdrop" onclick={() => close()}>
  </div>
</dialog>
