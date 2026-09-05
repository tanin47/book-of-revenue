<script lang="ts">
import CustomerListTemplate from "./_customer_list_template.svelte";
</script>

<CustomerListTemplate
  fetchUrl="/load-customer-list/nrr"
  exportCsvUrl="/export-customer-list/nrr"
  page="nrr"
/>

<style></style>
