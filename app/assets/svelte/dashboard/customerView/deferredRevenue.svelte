<script lang="ts">
import ListTemplate from "./_list_template.svelte";
import type {Customer} from "../../common/models";

export let customerId: string | null
export let customer: Customer | null
</script>

<ListTemplate
  {customerId}
  {customer}
  fetchUrl="/load-transaction-list/deferred-revenue"
  exportCsvUrl="/export-transaction-list/deferred-revenue"
  page="deferred-revenue"
/>

<style></style>
