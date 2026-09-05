<script lang="ts">
import CustomerListTemplate from "./_customer_list_template.svelte";
</script>

<CustomerListTemplate
  fetchUrl="/load-customer-list/other-contractual-liabilities"
  exportCsvUrl="/export-customer-list/other-contractual-liabilities"
  page="other-contractual-liabilities"
/>

<style></style>
