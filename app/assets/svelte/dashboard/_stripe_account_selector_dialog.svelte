<script lang="ts">
import {post, ValidationError} from "../common/form";
import type {StripeAccount} from "../common/models";
import ErrorPanel from "../common/form/_error_panel.svelte";
import {onMount} from "svelte";
import {CURRENT_STRIPE_ACCOUNT} from "../common/globals";

export let disableClose: boolean = false

let errors: string[] = []

let stripeAccounts: StripeAccount[] = []

async function load(): Promise<void> {
  try {
    const json = await post('/settings/load-stripe-accounts', {})

    stripeAccounts = json.stripeAccounts
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
  if (disableClose) { return; }
  modal.close()
}

function initials(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? '')
    .join('')
}

function switchAccount(account: StripeAccount, liveMode: boolean): void {
  window.location.href = `/switch-stripe-account/${account.id}/${liveMode ? 'live' : 'test'}`
}
</script>

<dialog bind:this={modal} class="modal2">
  <div class="modal-box !min-w-[440px] !w-auto !max-w-none flex flex-col gap-4 text-sm">
    <div class="flex items-center justify-between">
      <div class="flex flex-col">
        <span class="text-base font-bold">Switch account</span>
        <span class="text-xs text-gray-600">Select a Stripe account and mode.</span>
      </div>
      {#if !disableClose}
        <button
          type="button"
          title="Close"
          class="btn btn-sm btn-ghost btn-circle"
          onclick={() => close()}
        >
          <i class="ph-bold ph-x text-base"></i>
        </button>
      {/if}
    </div>

    <div class="flex flex-col gap-3 max-h-[50vh] overflow-y-auto">
      {#each stripeAccounts as account (account.id)}
        {@const isLiveModeActive = account.id === CURRENT_STRIPE_ACCOUNT?.stripeAccount.id && CURRENT_STRIPE_ACCOUNT?.liveMode}
        {@const isTestModeActive = account.id === CURRENT_STRIPE_ACCOUNT?.stripeAccount.id && !CURRENT_STRIPE_ACCOUNT?.liveMode}
        <div class="flex flex-col gap-2 rounded-lg border border-gray-600 p-3">
          <div class="flex items-center gap-2">
            <div class="avatar avatar-placeholder shrink-0">
              <div class="w-8 h-8 rounded-full bg-primary text-primary-content">
                <span class="text-xs font-semibold">{initials(account.name)}</span>
              </div>
            </div>
            <span class="text-sm font-semibold text-ellipsis whitespace-nowrap overflow-hidden">{account.name}</span>
          </div>

          <div class="flex flex-wrap gap-2">
            <button
              type="button"
              disabled={!account.liveModeEnabled}
              class="flex flex-1 items-center gap-2 rounded-md border px-3 py-2 text-left transition-colors
                {isLiveModeActive
                  ? 'border-primary bg-primary/10'
                  : account.liveModeEnabled
                    ? 'border-gray-600 hover:bg-base-200 cursor-pointer'
                    : 'border-gray-700 opacity-50 cursor-not-allowed'}"
              onclick={() => {
                if (account.liveModeEnabled) { switchAccount(account, true) }
              }}
            >
              <span class="inline-block w-1.5 h-1.5 rounded-full shrink-0 bg-success"></span>
              <span class="text-sm font-medium whitespace-nowrap">Live mode</span>
              {#if isLiveModeActive}
                <i class="ph-duotone ph-check text-base text-primary shrink-0 ml-auto"></i>
              {/if}
            </button>
            <button
              type="button"
              disabled={!account.testModeEnabled}
              class="flex flex-1 items-center gap-2 rounded-md border px-3 py-2 text-left transition-colors
                {isTestModeActive
                  ? 'border-primary bg-primary/10'
                  : account.testModeEnabled
                    ? 'border-gray-600 hover:bg-base-200 cursor-pointer'
                    : 'border-gray-700 opacity-50 cursor-not-allowed'}"
              onclick={() => {
                if (account.testModeEnabled) { switchAccount(account, false) }
              }}
            >
              <span class="inline-block w-1.5 h-1.5 rounded-full shrink-0 bg-gray-500"></span>
              <span class="text-sm font-medium whitespace-nowrap">Test mode</span>
              {#if isTestModeActive}
                <i class="ph font-bold ph-check text-base text-success shrink-0 ml-auto"></i>
              {/if}
            </button>
          </div>
        </div>
      {/each}
    </div>

    <a
      href="/settings"
      class="group flex items-center gap-3 rounded-lg border border-dashed border-gray-600 p-3 text-primary transition-colors hover:bg-primary/10 hover:border-primary"
    >
      <div class="flex items-center justify-center w-8 h-8 rounded-full bg-primary/10 shrink-0">
        <i class="ph-bold ph-plus text-base"></i>
      </div>
      <div class="flex flex-col min-w-0 flex-1 leading-tight">
        <span class="text-sm font-semibold">Add a Stripe account or mode</span>
        <span class="text-xs text-gray-600">Connect more accounts in Settings</span>
      </div>
      <i class="ph-bold ph-arrow-right text-base shrink-0 opacity-0 -translate-x-1 transition-all group-hover:opacity-100 group-hover:translate-x-0"></i>
    </a>
    <ErrorPanel {errors} />
  </div>
  <div class="modal-backdrop" onclick={() => close()}>
  </div>
</dialog>
