<script lang="ts">
import StripeAccountSelectorDialog from "./_stripe_account_selector_dialog.svelte";
import {CURRENT_STRIPE_ACCOUNT} from "../common/globals";

let dialog: StripeAccountSelectorDialog;

const name = CURRENT_STRIPE_ACCOUNT?.stripeAccount.name ?? '';
const liveMode = CURRENT_STRIPE_ACCOUNT?.liveMode;

const initials = name
  .split(/\s+/)
  .filter(Boolean)
  .slice(0, 2)
  .map((part) => part[0]?.toUpperCase() ?? "")
  .join("");
</script>

<StripeAccountSelectorDialog bind:this={dialog} />

<button
  type="button"
  title="Switch account"
  class="group min-w-[150px] w-full flex items-center gap-2 px-2 py-2 mb-1 rounded border border-transparent cursor-pointer overflow-hidden hover:bg-base-200 hover:border-gray-600 text-left"
  onclick={() => dialog.open()}
>
  <div class="avatar avatar-placeholder shrink-0">
    <div class="w-8 h-8 rounded-full bg-primary text-primary-content">
      <span class="text-xs font-semibold">{initials}</span>
    </div>
  </div>
  <div class="flex flex-col min-w-0 flex-1 leading-tight">
    <span class="text-sm font-semibold text-ellipsis whitespace-nowrap overflow-hidden">{name}</span>
    <span class="flex items-center gap-1 text-xs {liveMode ? 'text-success' : 'text-gray-600'}">
      <span class="inline-block w-1.5 h-1.5 rounded-full {liveMode ? 'bg-success' : 'bg-gray-500'}"></span>
      <span class="text-ellipsis whitespace-nowrap overflow-hidden">{liveMode ? 'Live mode' : 'Test mode'}</span>
    </span>
  </div>
  <i class="ph-duotone ph-caret-up-down text-base shrink-0 text-primary"></i>
</button>
