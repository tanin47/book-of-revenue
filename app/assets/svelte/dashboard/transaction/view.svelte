<script lang="ts">
import Layout from '../_layout.svelte'
import type {RevRecTransaction} from "../../common/models";
import TransactionDetail from "./_transaction_detail.svelte";
import DebitsAndCreditsView from "./_debits_and_credits_view.svelte";
import Button from "../../common/_button.svelte";
import AccountSummaryView from './_account_summary_view.svelte';
import {post} from "../../common/form";
import {onMount} from "svelte";

export let transaction: RevRecTransaction

function getStripeUrl(c: RevRecTransaction): string {
  const prefix = c.liveMode ? '' : 'test/'
  const base = `https://dashboard.stripe.com/${prefix}`
  switch (c.type) {
    case 'Invoice': return `${base}invoices/${c.id}`
    case 'StandalonePaymentIntent': return `${base}payments/${c.id}`
    case 'StandaloneCharge': return `${base}payments/${c.id}`
    default: return `${base}search?query=${encodeURIComponent(c.id)}`
  }
}

let debitsAndCreditsView: DebitsAndCreditsView | null = null
let accountSummaryView: AccountSummaryView | null = null
type View = 'debits-and-credits' | 'account-summary'
let view: View = 'account-summary'

const q = new URLSearchParams(window.location.search ?? '');
if (q.get('view') === 'debits-and-credits') {
  view = 'debits-and-credits'
}

onMount(() => {
  function loadPage() {
    updateParamsFromQueryString()
  }

  function popstate() {
    loadPage()
  }

  window.addEventListener('popstate', popstate)
  loadPage()

  return () => {
    window.removeEventListener('popstate', popstate)
  }
})


function switchTo(newView: View): void {
  const current =  new URLSearchParams(window.location.search ?? '');
  const q = new URLSearchParams();

  q.append('view', newView)

  const ilParam = current.get('il')
  if (ilParam) {
    q.append('il', ilParam)
  }

  const queryString = q.toString();
  history.pushState({queryString}, '', `${window.location.origin}${window.location.pathname}?${queryString}`)
  view = newView
}

function updateParamsFromQueryString() {
  const queryString = window.location.search;
  const q = new URLSearchParams(queryString ?? '');

  view = q.get('view') === 'debits-and-credits' ? 'debits-and-credits' : 'account-summary'
}

type ResizeMode = 'leftView' | null
let resizeMode: ResizeMode = null
let leftViewWidth = 370;
let initialClientX: number = 0
let initialLeftViewWidth: number = 0

function startResize(mode: ResizeMode, event: MouseEvent) {
  initialLeftViewWidth = leftViewWidth
  initialClientX = event.clientX
  resizeMode = mode;
  document.body.classList.add('resizing');
}

function stopResize() {
  resizeMode = null;
  document.body.classList.remove('resizing');
}

function handleResize(event: MouseEvent) {
  switch (resizeMode) {
    case 'leftView':
      leftViewWidth = Math.max(initialLeftViewWidth + event.clientX - initialClientX, 50);
      break;
    case null:
    // do nothing
  }
}

let isReprocessing = false
let isReprocessed = false

async function reprocess() {
  isReprocessing = true
  try {
    await post(`/reprocess-transaction`, {transactionId: transaction.id})
    if (debitsAndCreditsView) {
      await debitsAndCreditsView.refresh()
    }
    if (accountSummaryView) {
      await accountSummaryView.refresh()
    }
    isReprocessed = true
  } finally {
    isReprocessing = false
  }
}

</script>

<svelte:window on:mousemove={handleResize} on:mouseup={stopResize}/>

<Layout>
  <div class="flex flex-col w-full h-full grow overflow-hidden">
    <div class="flex flex-col grow w-full items-stretch overflow-hidden relative">
      <div class="bg-base-300 text-primary border-base-content border-b px-3 py-2 flex flex-col gap-3">
        <div class="text-xs flex gap-1 items-center">
          <a href="/customer" class="link">Customers</a>
          <span>&gt;</span>
          <a href="/customer/{transaction.customerId ?? 'empty'}" class="link">{transaction.customer?.name ?? transaction.customer?.email ?? transaction.customerId ?? '[empty]'}</a>
          <span>&gt;</span>
          <a href="/customer/transaction/{transaction.id}" class="link">{transaction.title ?? transaction.id}</a>
        </div>
        <div class="flex items-center justify-between gap-4">
          <div class="flex items-center gap-2.5 min-w-0">
            <i class="ph-duotone ph-receipt text-4xl"></i>
            <h1 class="font-bold text-base leading-tight truncate">{transaction.title ?? transaction.id}</h1>
          </div>
          <div class="flex items-center gap-2">
            <a href={getStripeUrl(transaction)} target="_blank" class="btn btn-xs btn-info shadow-none">View on Stripe</a>
            {#if isReprocessed}
              <Button class="btn btn-xs btn-success shadow-none btn-active cursor-default">
                <i class="ph ph-check"></i>
                Reprocessed
              </Button>
            {:else}
              <Button class="btn btn-xs btn-secondary shadow-none" isLoading={isReprocessing} onClick={() => {void reprocess()}}>Reprocess</Button>
            {/if}
          </div>
        </div>
      </div>
      <div class="flex items-stretch grow overflow-hidden">
        <div
          class="shrink-0 overflow-y-auto border-e border-base-content bg-base-100 relative"
          style="width: {leftViewWidth}px; min-width: {leftViewWidth}px; max-width: {leftViewWidth}px;"
        >
          <TransactionDetail {transaction} />
        </div>
        <div class="flex flex-col grow min-w-0 items-stretch overflow-hidden relative">
          <span
            class="absolute top-0 bottom-0 left-0 w-[5px] z-50 cursor-col-resize"
            onmousedown={(ev) => {startResize('leftView', ev)}}
          >&nbsp;</span>
          {#if view === 'debits-and-credits'}
            <DebitsAndCreditsView bind:this={debitsAndCreditsView} {transaction} onSwitchToAccountSummary={() => { switchTo('account-summary') }} />
          {:else}
            <AccountSummaryView bind:this={accountSummaryView} {transaction} onSwitchToDebitsCredits={() => { switchTo('debits-and-credits') }} />
          {/if}
        </div>
      </div>
    </div>
  </div>
</Layout>
