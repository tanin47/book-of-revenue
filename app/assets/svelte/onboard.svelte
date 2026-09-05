<script lang="ts">
import Button from './common/_button.svelte'
import ErrorPanel  from './common/form/_error_panel.svelte'
import {post, ValidationError} from "./common/form";
import {onMount} from "svelte";
import {fade} from "svelte/transition";

export let appDomain: string

let hasValidCert = false
let isLoading = false
let isCheckingCert = true
let errors: string[] = []

async function checkCertificate(polling: boolean): Promise<void> {
  try {
    const json = await post('/onboard/check-cert', {})

    hasValidCert = json.hasValidCert
  } catch (e) {
    if (e instanceof ValidationError) {
      errors = e.messages
    } else {
      console.error(e)
      errors = ['An unknown error occurred. Please contact your administrator.']
    }
  }

  if (hasValidCert) {
    isLoading = false
  } else {
    if (polling) {
      setTimeout(() => { checkCertificate(true) }, 10000)
    }
  }

  isCheckingCert = false
}

async function triggerCertificateIssuance(): Promise<void> {
  isLoading = true
  try {
    await post('/onboard/issue-cert', {})

    void checkCertificate(true)
  } catch (e) {
    console.log((e as ValidationError).messages)
    isLoading = false
  }
}

onMount(() => {
  void checkCertificate(false)
})
</script>

<div class="hero bg-base-200 min-h-screen">
  <div class="hero-content w-full flex-col justify-center items-center">
    <div class="flex flex-col items-center gap-2 text-center">
      <i class="ph-duotone ph-book-open-text text-primary text-4xl"></i>
      <h1 class="text-2xl font-bold">Welcome to Book of Revenue</h1>
      <p class="text-sm text-base-content/60">Let's finish setting up secure access to your instance.</p>
      <div class="mt-1 inline-flex items-center gap-2 rounded-full border border-primary/20 bg-primary/5 px-4 py-1.5 shadow-sm">
        <i class="ph-duotone ph-globe-hemisphere-west text-primary text-lg"></i>
        <span class="font-mono text-sm font-semibold tracking-tight text-base-content">{appDomain}</span>
      </div>
    </div>

    <div class="card bg-base-100 min-w-[400px] w-full max-w-md shrink-0 shadow-2xl">
      <div class="card-body items-center justify-center text-center gap-5 py-8 min-h-[280px]">
        {#if isCheckingCert}
          <div class="flex flex-col items-center gap-5 w-full" in:fade={{ duration: 150 }}>
            <span class="loading loading-spinner loading-lg text-primary"></span>
            <div class="flex flex-col gap-1">
              <span class="font-semibold">Checking your SSL certificate</span>
              <span class="text-sm text-base-content/60">Verifying that your domain has a valid certificate…</span>
            </div>
          </div>
        {:else if hasValidCert}
          <div class="flex flex-col items-center gap-5 w-full" in:fade={{ duration: 150 }}>
            <div class="flex items-center justify-center size-16 rounded-full bg-success/10 text-success">
              <i class="ph-duotone ph-shield-check text-4xl"></i>
            </div>
            <div class="flex flex-col gap-1">
              <span class="text-lg font-semibold">You're all set</span>
              <span class="text-sm text-base-content/60">Your domain has a valid SSL certificate.</span>
            </div>
            <a href="/register" class="btn btn-primary w-full">
              Go to Book of Revenue
              <i class="ph-bold ph-arrow-right"></i>
            </a>
          </div>
        {:else}
          <div class="flex flex-col items-center gap-5 w-full" in:fade={{ duration: 150 }}>
            <div class="flex items-center justify-center size-16 rounded-full bg-error/10 text-error">
              <i class="ph-duotone ph-shield-warning text-4xl"></i>
            </div>
            <div class="flex flex-col gap-1">
              <span class="text-lg font-semibold">No valid SSL certificate</span>
              <span class="text-sm text-base-content/60">
                Your domain doesn't have a valid certificate yet. Issue one for free with Let's Encrypt to enable secure access.
              </span>
            </div>
            <Button
              class="btn btn-primary w-full"
              onClick={triggerCertificateIssuance}
              {isLoading}
            >
              {#if isLoading}
                Waiting for the new certificate…
              {:else}
                <i class="ph-bold ph-lock-key"></i>
                Issue SSL certificate with Let's Encrypt
              {/if}
            </Button>
            {#if isLoading}
              <span class="text-xs text-base-content/50">This can take a minute. Keep this page open.</span>
            {/if}
            <ErrorPanel {errors} />
          </div>
        {/if}
      </div>
    </div>

    <p class="text-xs text-base-content/40 flex items-center gap-1">
      <i class="ph ph-lock-simple"></i>
      Secured with Let's Encrypt
    </p>
  </div>
</div>


<style lang="scss">
</style>
