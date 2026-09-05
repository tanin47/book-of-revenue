<script lang="ts">
import CustomerListTemplate from "./_customer_list_template.svelte";
</script>

<CustomerListTemplate
  fetchUrl="/load-customer-list/net-revenue"
  exportCsvUrl="/export-customer-list/net-revenue"
  page="net-revenue"
/>

<style></style>
