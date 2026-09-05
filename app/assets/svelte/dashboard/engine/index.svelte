<script lang="ts">
import Layout from '../_layout.svelte'
import {formatAmount, formatDateTime, formatNumber} from '../../common/globals'
import type {RevRecTransaction, TrackedException} from "../../common/models";
import {onMount} from "svelte";
import {post} from "../../common/form";

interface Stage {
  key: string,
  name: string
  description: string
  icon: string
  unit: string
}

const STAGES: Stage[] = [
  {
    key: 'importer',
    name: 'Importer',
    description: 'Pulls raw objects from the Stripe API.',
    icon: 'ph-cloud-arrow-down',
    unit: 'imported objects',
  },
  {
    key: 'transformer',
    name: 'Transformer',
    description: 'Normalizes raw objects into entities.',
    icon: 'ph-arrows-clockwise',
    unit: 'transformed objects',
  },
  {
    key: 'processor',
    name: 'Processor',
    description: 'Books entities into journal entries.',
    icon: 'ph-calculator',
    unit: 'processed transactions',
  }
]

interface StageData {
  count: number
  latestUpdatedAt: number | null
  isRunning: boolean
}

let isLoading = false;
let stageData: {[key: string]: StageData} = {}
let recentExceptions: TrackedException[] = []
let recentTransactions: RevRecTransaction[] = []
let isRunningAll = false
let loadTimeoutId: number | null = null
let isRunEngineTriggered = false

async function runAllStages(): Promise<void> {
  isRunningAll = true
  try {
    await post('/run-engine', {})
    isRunEngineTriggered = true
  } finally {
    isRunningAll = false
  }
}

function queueLoad(): void {
  if (loadTimeoutId !== null) {
    clearTimeout(loadTimeoutId)
  }
  loadTimeoutId = window.setTimeout(() => {void load()}, 10000)
}

async function load(): Promise<void> {
  isLoading = true;
  try {
    const json = await post('/load-engine', {})

    stageData = json.stages

    recentTransactions = json.recentTransactions
    recentExceptions = json.recentExceptions
  } finally {
    isLoading = false
    queueLoad()
  }
}

onMount(() => {
  void load();
})
</script>

<Layout>
  <div class="w-full h-full overflow-auto bg-base-200">
    <div class="max-w-5xl mx-auto w-full p-6 flex flex-col gap-6">
      <div class="flex items-center justify-between gap-3">
        <div class="flex items-center gap-3 min-w-0">
          <div class="flex items-center justify-center size-10 rounded-lg bg-primary/10 text-primary shrink-0">
            <i class="ph-duotone ph-engine text-2xl"></i>
          </div>
          <div class="flex flex-col min-w-0">
            <h1 class="text-xl font-bold leading-tight">Engine Stats</h1>
            <p class="text-sm text-base-content/60">How your Stripe data flows from import to journal entries.</p>
          </div>
        </div>
        <button
          class="btn btn-sm btn-primary gap-1.5 shrink-0"
          disabled={isRunningAll || isRunEngineTriggered}
          onclick={() => { void runAllStages() }}
        >
          {#if isRunEngineTriggered}
            <i class="ph-duotone ph-play text-base"></i>
            The run is triggered
          {:else if isRunningAll}
            <span class="loading loading-spinner loading-xs"></span>
            Starting…
          {:else}
            <i class="ph-duotone ph-play text-base"></i>
            Run all stages
          {/if}
        </button>
      </div>

      <div class="flex flex-col lg:flex-row items-stretch gap-2">
        {#each STAGES as stage, index (stage.key)}
          {@const running = stageData[stage.key]?.isRunning ?? false}
          {#if index > 0}
            <div class="flex items-center justify-center text-base-content/30 shrink-0 py-1 lg:py-0">
              <i class="ph-bold ph-arrow-down text-xl lg:hidden"></i>
              <i class="ph-bold ph-arrow-right text-xl hidden lg:block"></i>
            </div>
          {/if}
          <div
            class="card bg-base-100 border shadow-sm grow basis-0 {running ? 'border-success/50' : 'border-base-300'}"
          >
            <div class="card-body gap-4 p-5">
              <div class="flex items-start justify-between gap-2">
                <div class="flex items-center gap-2 min-w-0">
                  <div class="flex items-center justify-center size-9 rounded-lg shrink-0 {running ? 'bg-success/10 text-success' : 'bg-base-200 text-base-content/50'}">
                    <i class="ph-duotone {stage.icon} text-xl"></i>
                  </div>
                  <span class="font-semibold truncate">{stage.name}</span>
                </div>
                {#if running}
                  <span class="badge badge-success badge-sm gap-1.5 shrink-0">
                    <span class="relative flex size-2.5 shrink-0">
                      <span class="absolute inline-flex size-full rounded-full bg-white opacity-90 animate-ping"></span>
                      <span class="relative inline-flex size-2.5 rounded-full bg-white"></span>
                    </span>
                    Running
                  </span>
                {:else}
                  <span class="badge badge-ghost badge-sm gap-1.5 shrink-0 text-base-content/50">
                    <span class="inline-block size-1.5 rounded-full bg-current"></span>
                    Not running
                  </span>
                {/if}
              </div>

              <p class="text-sm text-base-content/60 leading-snug">{stage.description}</p>

              <div class="flex flex-col gap-0.5">
                <span class="text-3xl font-bold leading-none tabular-nums">{formatNumber(stageData[stage.key]?.count ?? 0)}</span>
                <span class="text-xs uppercase tracking-wide text-base-content/60">{stage.unit}</span>
              </div>

              <div class="flex items-center gap-1.5 text-xs text-base-content/60 border-t border-base-300 pt-3">
                <i class="ph-duotone ph-clock-countdown text-base shrink-0"></i>
                {#if stageData[stage.key]?.latestUpdatedAt}
                  <span class="truncate">Updated {formatDateTime(stageData[stage.key]?.latestUpdatedAt!)}</span>
                {:else}
                  <span class="truncate">Never run</span>
                {/if}
              </div>
            </div>
          </div>
        {/each}
      </div>

      <div class="card bg-base-100 border border-base-300 shadow-sm overflow-hidden">
        <div class="flex items-center gap-2 px-5 py-3 border-b border-base-300">
          <i class="ph-duotone ph-receipt text-lg text-base-content/60"></i>
          <span class="font-semibold grow">Recently updated transactions</span>
        </div>
        <div class="flex flex-col divide-y divide-base-300">
          {#each recentTransactions as transaction, index (index)}
            <div class="flex items-center justify-between gap-4 px-5 py-3">
              <div class="flex flex-col gap-2 min-w-0">
                <a
                  href="/customer/transaction/{transaction.id}"
                  class="link-hover font-medium truncate"
                  class:italic={!transaction.title}
                >{transaction.title ?? transaction.id}</a>
                <div class="flex items-center gap-1.5 text-xs text-base-content/60 min-w-0">
                  <i class="ph-duotone ph-user text-sm shrink-0"></i>
                  <a
                    href="/customer/{transaction.customerId ?? 'empty'}"
                    class="link-hover truncate"
                  >{transaction.customer?.name ?? transaction.customer?.email ?? transaction.customerId ?? '[empty]'}</a>
                  <span class="text-base-content/30 shrink-0">&bull;</span>
                  {#if transaction.syncedAt}
                    <span class="shrink-0 whitespace-nowrap">Updated at {formatDateTime(transaction.syncedAt)}</span>
                  {:else}
                    <span class="shrink-0 whitespace-nowrap italic">Not processed</span>
                  {/if}
                </div>
              </div>
              <div class="flex flex-col items-end gap-2 shrink-0">
                <span class="font-semibold tabular-nums leading-none">
                  {#if transaction.settlementTotalValue !== null && transaction.settlementCurrency !== null}
                    {formatAmount(transaction.settlementTotalValue, transaction.settlementCurrency)}
                  {:else}
                    &mdash;
                  {/if}
                </span>
              </div>
            </div>
          {/each}
        </div>
      </div>

      <div class="card bg-base-100 border border-base-300 shadow-sm overflow-hidden">
        <div class="flex items-center gap-2 px-5 py-3 border-b border-base-300">
          <i class="ph-duotone ph-warning-circle text-lg text-error"></i>
          <span class="font-semibold grow">Latest exceptions</span>
        </div>
        <div class="flex flex-col divide-y divide-base-300">
          {#each recentExceptions as exception, index (index)}
            <div class="flex flex-col gap-2 px-5 py-4">
              <div class="flex items-baseline justify-between gap-3">
                <span class="font-mono text-sm font-medium text-error truncate">{exception.exceptionClass}</span>
                <span class="text-xs text-base-content/60 shrink-0">{formatDateTime(exception.createdAt)}</span>
              </div>
              <p class="text-sm">{exception.message}</p>
              <details class="group/details">
                <summary class="flex items-center gap-1 text-xs text-base-content/60 cursor-pointer w-fit hover:text-base-content">
                  <i class="ph-bold ph-caret-right text-xs transition-transform group-open/details:rotate-90"></i>
                  Stack trace
                </summary>
                <pre class="mt-2 p-3 rounded-lg bg-base-200 text-xs font-mono overflow-x-auto whitespace-pre">{exception.stackTrace}</pre>
              </details>
            </div>
          {/each}
        </div>
      </div>
    </div>
  </div>
</Layout>
