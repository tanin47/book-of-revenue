<script lang="ts">
import BarChart, {type DataPoint} from "./_bar_chart.svelte";
import {formatAccountingPeriod, getNetRevenueAuditUrl} from "../common/globals";

export let points: DataPoint[]

</script>

<div class="card-title flex items-baseline justify-between gap-4">
  <span class="text-ellipsis whitespace-nowrap overflow-hidden">Net revenue</span>
</div>
<div class="py-4">
  {#if points.length > 0}
    <BarChart
      points={points}
      color="green"
      mainLabel="Net revenue"
      valueType="amount"
      getAuditUrl={(point, value) =>  getNetRevenueAuditUrl(point.period!, 'Transaction', value?.id ?? null)}
    />
  {/if}
</div>

<style lang="scss">
</style>
