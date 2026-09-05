<script lang="ts">
import CustomerListTemplate from "./_customer_list_template.svelte";
</script>

<CustomerListTemplate
  fetchUrl="/load-customer-list/deferred-revenue"
  exportCsvUrl="/export-customer-list/deferred-revenue"
  page="deferred-revenue"
/>

<style></style>
