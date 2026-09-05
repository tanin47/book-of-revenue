<script lang="ts">
import BarChart, {type DataPoint} from "./_bar_chart.svelte";
import {post} from "../common/form";
import type {Params} from "./_filter_dialog.svelte";

export let points: DataPoint[] = []
</script>

<div class="card-title flex items-baseline justify-between gap-4">
  <span class="text-ellipsis whitespace-nowrap overflow-hidden">Other contractual liability change</span>
</div>
<div class="py-4">
  {#if points.length > 0}
    <BarChart points={points} color="green" mainLabel="Net change" valueType="amount"/>
  {/if}
</div>

<style lang="scss">
</style>
