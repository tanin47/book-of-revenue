<script lang="ts">
import BarChart, {type DataPoint} from "./_bar_chart.svelte";
import {FIRST_ACCOUNTING_PERIOD, getDeferredRevenueAuditUrl, LATEST_ACCOUNTING_PERIOD} from "../common/globals";
import {onMount} from "svelte";
import {post} from "../common/form";
import type {Params} from "./_filter_dialog.svelte";

export let points: DataPoint[] = []
</script>

<div class="card-title flex items-baseline justify-between gap-4">
  <span class="text-ellipsis whitespace-nowrap overflow-hidden">Deferred revenue ending balance</span>
</div>
<div class="py-4">
  {#if points.length > 0}
    <BarChart
      points={points}
      color="green"
      mainLabel="Ending balance"
      valueType="amount"
      getAuditUrl="{(point) => getDeferredRevenueAuditUrl(point.period!)}"
    />
  {/if}
</div>

<style lang="scss">
</style>
