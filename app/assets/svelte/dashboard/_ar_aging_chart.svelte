<script context="module" lang="ts">
export interface ArAgingDataPoint {
  exclusiveUpUntil: number
  notDue: number
  days30: number
  days60: number
  days90: number
  days120: number
  days120Plus: number
}

</script>
<script lang="ts">
import BarChart, {type DataPoint} from "./_bar_chart.svelte";
import {post} from "../common/form";
import type {Params} from "./_filter_dialog.svelte";
import {addMonths, getArAgingAuditUrl} from "../common/globals";

export let params: Params
export let points: ArAgingDataPoint | null
let chartPoints: DataPoint[] = []

$: {
  chartPoints = [
    {label: 'Not due', value: points?.notDue ?? 0},
    {label: '30 days', value: points?.days30 ?? 0},
    {label: '60 days', value: points?.days60 ?? 0},
    {label: '90 days', value: points?.days90 ?? 0},
    {label: '120 days', value: points?.days120 ?? 0},
    {label: '>120 days', value: points?.days120Plus ?? 0},
  ]
}

</script>

 <div class="card-title flex items-baseline justify-between gap-4">
  <div class="flex items-baseline gap-2">
    <h2 class="text-ellipsis whitespace-nowrap overflow-hidden">
      AR Aging
    </h2>
    <span class="text-xs font-normal italic">as of {new Date(params.periodEnd).toISOString().substring(0, 7)}</span>
  </div>
</div>
<div class="py-4">
  {#if chartPoints.length > 0}
    <BarChart
      points={chartPoints}
      color="red"
      mainLabel="AR"
      valueType="amount"
      getAuditUrl={(point) => getArAgingAuditUrl(addMonths(params.periodEnd, 1))}
    />
  {/if}
</div>

<style lang="scss">
</style>
