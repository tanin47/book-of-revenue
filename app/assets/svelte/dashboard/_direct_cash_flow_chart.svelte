<script lang="ts">
import BarChart, {type DataPoint} from "./_bar_chart.svelte";
import {onMount} from "svelte";
import {post} from "../common/form";
import type {Params} from "./_filter_dialog.svelte";
import {getDirectCashFlowAuditUrl} from "../common/globals";

export let points: DataPoint[] = []

</script>

<div class="card-title flex items-baseline justify-between gap-4">
  <span class="text-ellipsis whitespace-nowrap overflow-hidden">Direct cash flow</span>
</div>
<div class="py-4">
  {#if points.length > 0}
    <BarChart
      points={points}
      color="green"
      mainLabel="Net cash change"
      valueType="amount"
      getAuditUrl={(point, value) => getDirectCashFlowAuditUrl(point.period!)}
    />
  {/if}
</div>

<style lang="scss">
</style>
