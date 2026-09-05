<script lang="ts">
import BarChart, {type DataPoint} from "./_bar_chart.svelte";
import {formatAccountingPeriod, getMonthlyGrrAuditUrl} from "../common/globals";

export let points: DataPoint[] = []

</script>

<div class="card-title flex items-baseline justify-between gap-4">
  <span class="text-ellipsis whitespace-nowrap overflow-hidden">Monthly GRR</span>
</div>
<div class="py-4">
  {#if points.length > 0}
    <BarChart
      points={points}
      color="green"
      mainLabel="GRR"
      valueType="percentage"
      getAuditUrl={(point, value) => getMonthlyGrrAuditUrl(point.period!)}
    />
  {/if}
</div>

<style lang="scss">
</style>
