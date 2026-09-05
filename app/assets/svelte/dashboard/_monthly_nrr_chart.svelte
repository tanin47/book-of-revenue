<script lang="ts">
import BarChart, {type DataPoint} from "./_bar_chart.svelte";
import {
  FIRST_ACCOUNTING_PERIOD,
  formatAccountingPeriod,
  getMonthlyNrrAuditUrl,
  LATEST_ACCOUNTING_PERIOD
} from "../common/globals";
import {onMount} from "svelte";
import {post} from "../common/form";
import type {Params} from "./_filter_dialog.svelte";

export let points: DataPoint[] = []

</script>

<div class="card-title flex items-baseline justify-between gap-4">
  <span class="text-ellipsis whitespace-nowrap overflow-hidden">Monthly NRR</span>
</div>
<div class="py-4">
  {#if points.length > 0}
    <BarChart
      points={points}
      color="green"
      mainLabel="NRR"
      valueType="percentage"
      getAuditUrl={(point, value) => getMonthlyNrrAuditUrl(point.period!)}
    />
  {/if}
</div>

<style lang="scss">
</style>
