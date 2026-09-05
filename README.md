Book of Revenue
================

_Book of Revenue (BOR)_ is an open-source revenue recognition and analytics for Stripe. Built by ex-Stripe engineer.

> [!NOTE]
> __Do you know how much you earned last month?__
> 
> Not payments collected. Not MRR. Under the accounting standards, revenue is earned when the service is delivered, reversed on a refund, and adjusted for dozens of scenarios in between — prorations, upgrades, credit notes, disputes.
> 
> _Deploy in minutes and see your correct revenue today._

BOR is a drop-in replacement for Stripe Revenue Recognition and offers revenue analytics that are aligned with the accounting standards.

Demo: [demo.bookofrevenue.com](https://demo.bookofrevenue.com) (username: controller, password: 1234)

![Overview](./screenshots/overview.png)

__Key Features:__
* __Standard-Compliant:__ Translates Stripe transactions into precise journal entries that are aligned with the ASC 606 (for US) and IFRS 15 (global) accounting standards.
* __Comprehensive Billing Coverage__: Handles all Stripe Billing scenarios out of the box—including upgrades, downgrades, usage-based billing, credit grants, discounts, credit notes, taxes, uncollectibles, voids, multi-currency billing, and more.
* __Accounting-grade Analytics:__ Delivers revenue analytics that automatically ties back to your accounting data.
* __Near-Real-Time Data:__ Provides continuous, up-to-date visibility into your financial health. The current latency is ~45 minutes.
* __Fully Auditable:__ Maintains audit-ready precision with full visibility into every transaction's journal entries, from executive summaries to individual line items.
* __Customizable (coming soon!):__ Supports powerful custom revenue allocation rules, chart of accounts (COA) mapping, service period overrides, transaction exclusions, and date overrides (e.g. void date, uncollectible date).

__Comprehensive Reporting:__
* Net revenue (i.e. monthly recognized revenue)
* Net revenue waterfall
* Deferred revenue
* AR Aging (aka overdue invoiced amount)
* Direct cash flow
* Net revenue retention (NRR)
* Gross revenue retention (GRR)
* Monthly active customers (i.e. the customers that contribute to revenue in a month)
* Contractual liabilities (e.g. customer balance, credit grants)
* Debits & credits
* Balance sheet
* Income statement

Website: [bookofrevenue.com](https://bookofrevenue.com)

Questions? Contact us at [tanin (at) bookofrevenue.com](mailto:tanin@bookofrevenue.com)


Screenshots
------------------

Click on a screenshot to see it in full size.

<table>
  <tr>
    <td width="33%" align="center">
      <a href="./screenshots/overview.png"><img src="./screenshots/thumbnails/overview.png" width="100%" alt="Overview" /></a>
      <br />
      Overview
    </td>
    <td width="33%" align="center">
      <a href="./screenshots/overview_more_charts_1.png"><img src="./screenshots/thumbnails/overview_more_charts_1.png" width="100%" alt="Overview (more charts 1)" /></a>
      <br />
      Overview (more charts 1)
    </td>
    <td width="33%" align="center">
      <a href="./screenshots/overview_more_charts_2.png"><img src="./screenshots/thumbnails/overview_more_charts_2.png" width="100%" alt="Overview (more charts 2)" /></a>
      <br />
      Overview (more charts 2)
    </td>
  </tr>
  <tr>
    <td width="33%" align="center">
      <a href="./screenshots/net_revenue_by_customer.png"><img src="./screenshots/thumbnails/net_revenue_by_customer.png" width="100%" alt="Net revenue by customer and month" /></a>
      <br />
      Net revenue by customer and month
    </td>
    <td width="33%" align="center">
      <a href="./screenshots/grr_by_customer.png"><img src="./screenshots/thumbnails/grr_by_customer.png" width="100%" alt="GRR by customer and month" /></a>
      <br />
      GRR by customer and month
    </td>
    <td width="33%" align="center">
      <a href="./screenshots/deferred_revenue_by_customer.png"><img src="./screenshots/thumbnails/deferred_revenue_by_customer.png" width="100%" alt="Deferred revenue by customer and month" /></a>
      <br />
      Deferred revenue by customer and month
    </td>
  </tr>
  <tr>
    <td width="33%" align="center">
      <a href="./screenshots/net_revenue_by_product.png"><img src="./screenshots/thumbnails/net_revenue_by_product.png" width="100%" alt="Net revenue by product and month" /></a>
      <br />
      Net revenue by product and month
    </td>
    <td width="33%" align="center">
      <a href="./screenshots/net_revenue_breakdown_for_a_specific_product_month.png"><img src="./screenshots/thumbnails/net_revenue_breakdown_for_a_specific_product_month.png" width="100%" alt="Net revenue breakdown for one specific product in a certain month" /></a>
      <br />
      Net revenue breakdown for one specific product in a certain month
    </td>
    <td width="33%" align="center">
      <a href="./screenshots/net_revenue_waterfall.png"><img src="./screenshots/thumbnails/net_revenue_waterfall.png" width="100%" alt="Net revenue waterfall" /></a>
      <br />
      Net revenue waterfall
    </td>
  </tr>
  <tr>
    <td width="33%" align="center">
      <a href="./screenshots/net_revenue_waterfall_by_customer.png"><img src="./screenshots/thumbnails/net_revenue_waterfall_by_customer.png" width="100%" alt="Net revenue waterfall by customer" /></a>
      <br />
      Net revenue waterfall by customer
    </td>
    <td width="33%" align="center">
      <a href="./screenshots/ar_aging_by_customer.png"><img src="./screenshots/thumbnails/ar_aging_by_customer.png" width="100%" alt="AR aging by customer" /></a>
      <br />
      AR aging by customer
    </td>
    <td width="33%" align="center">
      <a href="./screenshots/deferred_revenue_inflow_outflow.png"><img src="./screenshots/thumbnails/deferred_revenue_inflow_outflow.png" width="100%" alt="Deferred revenue inflow and outflow" /></a>
      <br />
      Deferred revenue inflow and outflow
    </td>
  </tr>
  <tr>
    <td width="33%" align="center">
      <a href="./screenshots/debits_and_credits.png"><img src="./screenshots/thumbnails/debits_and_credits.png" width="100%" alt="Debits &amp; credits" /></a>
      <br />
      Debits &amp; credits
    </td>
    <td width="33%" align="center">
      <a href="./screenshots/income_statement.png"><img src="./screenshots/thumbnails/income_statement.png" width="100%" alt="Income statement" /></a>
      <br />
      Income statement
    </td>
    <td width="33%" align="center">
      <a href="./screenshots/balance_sheet.png"><img src="./screenshots/thumbnails/balance_sheet.png" width="100%" alt="Balance sheet" /></a>
      <br />
      Balance sheet
    </td>
  </tr>
  <tr>
    <td width="33%" align="center">
      <a href="./screenshots/transaction_summary.png"><img src="./screenshots/thumbnails/transaction_summary.png" width="100%" alt="Transaction (account summary)" /></a>
      <br />
      Transaction (account summary)
    </td>
    <td width="33%" align="center">
      <a href="./screenshots/transaction_debits_credits.png"><img src="./screenshots/thumbnails/transaction_debits_credits.png" width="100%" alt="Transaction (debits &amp; credits)" /></a>
      <br />
      Transaction (debits &amp; credits)
    </td>
    <td width="33%"></td>
  </tr>
</table>


Deploy 
---------

Requirements:
* A machine (e.g. dedicated machine, VPS, EC2, Render) that supports Docker
* A domain name (can be an IP address)
* A Postgres database or a persistent disk for deploying a Postgres instance
  * BOR can manage its own Postgres instance if there's a persistent disk.
  * For EC2 and Render, we recommend attaching a persistent disk. It's simpler to manage and cheaper.
* A Stripe API key (supports the live, test, and sandbox mode)

There are two ways: (1) use a managed Postgres instance or (2) let BOR manage its own Postgres on a persistent disk.


__<u>Use a managed Postgres instance</u>__

Deploy the docker image: `tanin47/book-of-revenue:<VERSION>` (see Releases for the latest version) and set the following environment variables:

* `DATABASE_URL`: a Postgres database URL. The format:  `postgres://USER:PASSWORD@HOST:PORT/DATABASE_NAME`
* `APP_DOMAIN`: the domain name of your BOR instance e.g. `test.bookofrevenue.com`
* `COOKIE_SECRET_KEY`: any random string that is at least 32 characters long. This is used as the encryption key for the cookies

__<u>Let BOR manage its own Postgres on persistent disk</u>__

1. SSH into your machine
2. Install Docker
3. Make a new directory for BOR and `cd` into the directory
4. Run `bash <(curl -sSL https://bookofrevenue.com/install.sh)`
5. Follow the instructions to set up the app domain and the data directory path in the `.env` file
6. Visit `http://APP_DOMAIN` (not https) to set up an SSL certificate with Let's Encrypt
7. After setting up the SSL certificate, you will be asked to register with the username, password, and a Stripe API key.


Analytics and tracking
-----------------------

Self-hosted BOR doesn't track nor collect any data. 


Contributing
-------------

Please see [DEVELOP.md](./DEVELOP.md)
