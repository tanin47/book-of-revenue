<script lang="ts">
import CurrencySelectorDialog from "./_currency_selector_dialog.svelte";
import {CURRENT_CURRENCY} from "../common/globals";

let dialog: CurrencySelectorDialog;
</script>

<CurrencySelectorDialog bind:this={dialog} />

<button
  type="button"
  title="Change currency"
  class="group min-w-[150px] w-full flex items-center gap-2 px-2 py-1 mb-1 rounded border border-gray-600 cursor-pointer overflow-hidden hover:bg-base-200 text-left"
  onclick={() => dialog.open()}
>
  <i class="ph-duotone ph-currency-circle-dollar text-base shrink-0 text-primary"></i>
  <span class="text-sm font-semibold uppercase flex-1 min-w-0 text-ellipsis whitespace-nowrap overflow-hidden">{CURRENT_CURRENCY}</span>
  <i class="ph-duotone ph-caret-up-down text-base shrink-0 text-primary"></i>
</button>
