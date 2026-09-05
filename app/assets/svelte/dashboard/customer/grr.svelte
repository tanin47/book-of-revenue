<script lang="ts">
import CustomerListTemplate from "./_customer_list_template.svelte";
</script>

<CustomerListTemplate
  fetchUrl="/load-customer-list/grr"
  exportCsvUrl="/export-customer-list/grr"
  page="grr"
/>

<style></style>
