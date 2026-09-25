package framework

import org.jooq.conf.{RenderQuotedNames, Settings}
import org.jooq.impl.DSL
import org.jooq.types.YearToMonth
import org.jooq.{DSLContext, Field, QueryPart, SQLDialect}
import play.api.Logger
import slick.jdbc.{SQLActionBuilder, SetParameter}

object Jooq {
  private val logger = Logger(getClass)
  val ctx: DSLContext = DSL.using(
    SQLDialect.POSTGRES,
    new Settings()
      .withRenderFormatted(true)
      .withRenderQuotedNames(RenderQuotedNames.ALWAYS)
  )

  def getSql(queryPart: QueryPart): String = {
    val result = ctx.renderInlined(queryPart)
    logger.info(s"SQL: $result")
    result
  }

  def toSqlActionBuilder(queryPart: QueryPart): SQLActionBuilder = {
    SQLActionBuilder(getSql(queryPart), SetParameter.SetNothing)
  }

  def atTimeZoneUtc(col: Field[Instant]): Field[Instant] = {
    DSL.field("{0} AT TIME ZONE 'UTC'", classOf[Instant], col)
  }

  def addMonthsUtc(col: Field[Instant], numMonths: Int): Field[Instant] = {
    atTimeZoneUtc(atTimeZoneUtc(col).add(YearToMonth.valueOf(s"0-$numMonths")))
  }

  type JLong = java.lang.Long
}
