<script lang="ts">
import Layout from '../_layout.svelte'
import {post, ValidationError} from '../../common/form'
import {onMount} from 'svelte'
import type {StripeAccount} from "../../common/models";
import ErrorPanel from '../../common/form/_error_panel.svelte';

let stripeAccounts: StripeAccount[] = []
let isLoading = true
let errors: string[] = []

let newApiKey = ''
let isAdding = false
let addErrors: string[] = []

async function addApiKey(): Promise<void> {
  isAdding = true
  addErrors = []
  try {
    await post('/settings/add-new-api-key', {apiKey: newApiKey.trim()})

    // Refresh the page to show the newly connected account.
    window.location.reload()
  } catch (e) {
    if (e instanceof ValidationError) {
      addErrors = e.messages
    } else {
      console.error(e)
      addErrors = ['Could not add the API key. Please double-check it and try again.']
    }
    isAdding = false
  }
}

async function loadStripeAccounts(): Promise<void> {
  isLoading = true
  errors = []
  try {
    const json = await post('/settings/load-stripe-accounts', {})
    stripeAccounts = json.stripeAccounts
  } catch (e) {
    if (e instanceof ValidationError) {
      errors = e.messages
    } else {
      console.error(e)
      errors = ['An unknown error occurred. Please contact your administrator.']
    }
  } finally {
    isLoading = false
  }
}

let removingKey: string | null = null

function apiKeyModes(account: StripeAccount) {
  return [
    {label: 'Live mode', liveMode: true, enabled: account.liveModeEnabled},
    {label: 'Test mode', liveMode: false, enabled: account.testModeEnabled},
  ]
}

async function removeApiKey(accountId: string, liveMode: boolean): Promise<void> {
  const modeLabel = liveMode ? 'live mode' : 'test mode'
  if (!window.confirm(`Remove the ${modeLabel} API key? Book of Revenue will stop syncing this account.`)) { return }

  removingKey = `${accountId}:${liveMode}`
  errors = []
  try {
    await post('/settings/remove-api-key', {stripeAccountId: accountId, liveMode})
    await loadStripeAccounts()
  } catch (e) {
    if (e instanceof ValidationError) {
      errors = e.messages
    } else {
      console.error(e)
      errors = ['Could not remove the API key. Please try again.']
    }
  } finally {
    removingKey = null
  }
}

onMount(() => {
  void loadStripeAccounts()
})
</script>

<Layout>
  <div class="w-full h-full overflow-auto bg-base-200">
    <div class="max-w-3xl mx-auto w-full p-6 flex flex-col gap-6">
      <div class="flex items-center gap-3">
        <div class="flex items-center justify-center size-10 rounded-lg bg-primary/10 text-primary">
          <i class="ph-duotone ph-gear text-2xl"></i>
        </div>
        <div class="flex flex-col">
          <h1 class="text-xl font-bold leading-tight">Settings</h1>
          <p class="text-sm text-base-content/60">Manage the Stripe accounts connected to Book of Revenue.</p>
        </div>
      </div>

      {#if isLoading}
        <div class="card bg-base-100 border border-base-300 shadow-sm">
          <div class="card-body items-center justify-center gap-3 py-10">
            <span class="loading loading-spinner loading-lg text-primary"></span>
            <span class="text-sm text-base-content/60">Loading your Stripe accounts…</span>
          </div>
        </div>
      {:else if errors.length > 0}
        <div class="alert alert-error">
          <i class="ph-bold ph-warning-circle"></i>
          <div class="flex flex-col">
            {#each errors as error (error)}
              <span>{error}</span>
            {/each}
          </div>
        </div>
      {:else if stripeAccounts.length === 0}
        <div class="card bg-base-100 border border-base-300 shadow-sm">
          <div class="card-body items-center text-center gap-2 py-10">
            <div class="flex items-center justify-center size-12 rounded-full bg-base-200 text-base-content/50">
              <i class="ph-duotone ph-plugs text-3xl"></i>
            </div>
            <span class="font-semibold">No Stripe account connected</span>
            <span class="text-sm text-base-content/60">Add a Stripe secret key below to connect your account.</span>
          </div>
        </div>
      {:else}
        {#each stripeAccounts as account (account.id)}
          <div class="card bg-base-100 border border-base-300 shadow-sm">
            <div class="card-body gap-6">
              <div class="flex items-center gap-3 min-w-0">
                <div class="flex items-center justify-center size-11 rounded-lg text-[#635bff] shrink-0">
                  <i class="ph-duotone ph-stripe-logo text-5xl"></i>
                </div>
                <span class="font-semibold truncate text-2xl">{account.name}</span>
              </div>
              <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div class="flex flex-col gap-1">
                  <span class="text-xs uppercase text-base-content">Account ID</span>
                  <span class="font-bold">{account.id}</span>
                </div>
                <div class="flex flex-col gap-1">
                  <span class="text-xs uppercase text-base-content">Default currency</span>
                  <span class="font-bold">{account.defaultCurrency}</span>
                </div>
              </div>
              <div class="flex flex-col gap-2">
                <span class="text-xs uppercase text-base-content">API keys</span>
                <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
                  {#each apiKeyModes(account) as mode (mode.label)}
                    <div class="flex items-center justify-between gap-3 rounded-lg border border-base-300 px-3 py-2.5">
                      <div class="flex items-center gap-2 min-w-0">
                        <i class="ph-duotone ph-key text-lg text-base-content"></i>
                        <span class="text-sm font-medium">{mode.label}</span>
                      </div>
                      {#if mode.enabled}
                        <div class="flex items-center gap-1 shrink-0">
                          <span class="badge badge-success badge-sm gap-1">
                            Enabled
                          </span>
                          <button
                            class="btn btn-ghost btn-xs text-neutral"
                            title="Remove {mode.label} API key"
                            disabled={removingKey !== null}
                            onclick={() => { void removeApiKey(account.id, mode.liveMode) }}
                          >
                            {#if removingKey === `${account.id}:${mode.liveMode}`}
                              <span class="loading loading-spinner loading-xs"></span>
                            {:else}
                              <i class="ph-duotone ph-trash text-lg"></i>
                            {/if}
                          </button>
                        </div>
                      {:else}
                        <span class="badge badge-ghost badge-sm gap-1 shrink-0 text-base-content/50">
                          Not set
                        </span>
                      {/if}
                    </div>
                  {/each}
                </div>
              </div>
            </div>
          </div>
        {/each}
      {/if}

      <div class="card bg-base-100 border border-base-300 shadow-sm">
        <div class="card-body gap-3">
          <div class="flex flex-col gap-0.5">
            <span class="font-semibold text-lg">Add a new API key</span>
            <p class="text-sm text-base-content">Paste a Stripe secret key to connect live or test mode.</p>
          </div>
          <div class="flex flex-col sm:flex-row gap-2">
            <input
              type="text"
              class="input grow"
              placeholder="sk_live_…  or  sk_test_…"
              bind:value={newApiKey}
              disabled={isAdding}
              onkeydown={(e) => { if (e.key === 'Enter' && newApiKey.trim().length > 0) { void addApiKey() } }}
            />
            <button
              class="btn btn-primary"
              disabled={isAdding || newApiKey.trim().length === 0}
              onclick={() => { void addApiKey() }}
            >
              {#if isAdding}
                <span class="loading loading-spinner loading-xs"></span>
                Adding…
              {:else}
                Add API key
              {/if}
            </button>
          </div>
          <ErrorPanel errors={addErrors} />
          <p class="flex items-center gap-1.5 text-xs text-base-content">
            <i class="ph-duotone ph-info text-base"></i>
            <span>
              The API key must have the <b>Read</b> access to <b>Core</b>, <b>Billing</b>, and <b>Connect</b>.
            </span>
          </p>
        </div>
      </div>
    </div>
  </div>
</Layout>


<style lang="scss">
</style>
