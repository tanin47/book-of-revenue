<script lang="ts" context="module">
export type Page = 'net-revenue'| 'deferred-revenue'
</script>
<script lang="ts">
import SearchInput from "../../common/_search_input.svelte";
import {formatAccountingPeriod, formatNumber, getValidPeriods} from "../../common/globals";
import type {GlobalParams} from "./common";
import Button from "../../common/_button.svelte";

export let totalNumberOfRows: number;
export let params: GlobalParams;
export let currentPage: Page;
export let onChange: () => void;
export let onExport: () => Promise<void>;

function search(newKeyword: string) {
  params.keyword = newKeyword
  params = params
  onChange();
}

let isExporting = false;
async function exportCsv(): Promise<void> {
  isExporting = true;

  try {
    await onExport()
  } catch (e) {
    console.error(e)
  } finally {
    isExporting = false;
  }
}

const METRIC_LINKS = [
  {value: 'net-revenue', name: 'Net revenue'},
  {value: 'deferred-revenue', name: 'Deferred revenue balance'},
]
</script>
<div class="flex justify-between gap-2 items-stretch bg-base-200 text-primary border-base-content border-b text-xs">
  <div class="flex items-stretch gap-0">
    <span class="font-bold py-1 border-e border-base-content px-2 flex items-center">Total: {formatNumber(totalNumberOfRows)}</span>
    <div class="flex gap-3 items-center px-2 py-1">
      <div class="flex gap-2 items-center text-xs font-bold">
        <span>From:</span>
        <select
          bind:value={params.periodStart}
          class="select select-xs select-bordered bg-base-100"
          onchange={() => {onChange()}}
        >
          {#each getValidPeriods() as period (period)}
            <option value={period}>{formatAccountingPeriod(period)}</option>
          {/each}
        </select>
        <span>to</span>
        <select
          bind:value={params.periodEnd}
          class="select select-xs select-bordered bg-base-100"
          onchange={() => {onChange()}}
        >
          {#each getValidPeriods() as period (period)}
            <option value={period}>{formatAccountingPeriod(period)}</option>
          {/each}
        </select>
      </div>
      <div class="flex items-center gap-2 font-bold">
        <span>View:</span>
        <select
          bind:value={currentPage}
          class="select select-xs select-bordered bg-base-100"
          onchange={(ev) => {
            window.location.href = `/product/${(ev.target as any).value}${window.location.search}`
          }}
        >
          {#each METRIC_LINKS as metric (metric.value)}
            <option value={metric.value}>{metric.name}</option>
          {/each}
        </select>
      </div>
    </div>
  </div>
  <div class="flex items-stretch justify-stretch gap-0">
    <div class="px-2 py-1 flex items-center">
      <Button class="btn btn-xs btn-info shadow-none" isLoading={isExporting} onClick={() => {void exportCsv()}}>Export</Button>
    </div>
    <div class="flex items-center gap-1 border-s border-base-content p-1 px-2 focus-within:bg-base-100">
      <SearchInput keyword={params.keyword} onSearch={search} />
    </div>
  </div>
</div>
