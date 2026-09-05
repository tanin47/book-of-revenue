package framework

import com.github.tminglei.slickpg.{ExPostgresProfile, PgArraySupport}
import slick.ast.BaseTypedType
import slick.jdbc.{GetResult, PositionedParameters, PositionedResult, SQLActionBuilder, SetParameter}
import slick.lifted.OptionMapper2

import java.sql.Timestamp
import java.time.{OffsetDateTime, ZoneId}
import scala.reflect.ClassTag

trait SlickEnumSupport {
  driver: PostgresProfile =>

  trait SlickEnumApi {
    self: ExtPostgresAPI =>
    implicit def baseEnumMapper[T <: Enum[T]](implicit clazz: ClassTag[T]): BaseColumnType[T] = {
      val method = clazz.runtimeClass.getMethod("valueOf", classOf[String])
      MappedJdbcType.base[T, String](
        tmap = _.name(),
        tcomap = (name) => method.invoke(null, name).asInstanceOf[T]
      )
    }

    // See why we need the below here: https://github.com/slick/slick/issues/1986
    implicit def getOptionMapper2TT[B1, B2 <: Enum[B2]: BaseTypedType, P2 <: B2, BR]
      : OptionMapper2[B1, B2, BR, B1, P2, BR] = OptionMapper2.plain.asInstanceOf[OptionMapper2[B1, B2, BR, B1, P2, BR]]
    implicit def getOptionMapper2TO[B1, B2 <: Enum[B2]: BaseTypedType, P2 <: B2, BR]
      : OptionMapper2[B1, B2, BR, B1, Option[P2], Option[BR]] =
      OptionMapper2.option.asInstanceOf[OptionMapper2[B1, B2, BR, B1, Option[P2], Option[BR]]]
    implicit def getOptionMapper2OT[B1, B2 <: Enum[B2]: BaseTypedType, P2 <: B2, BR]
      : OptionMapper2[B1, B2, BR, Option[B1], P2, Option[BR]] =
      OptionMapper2.option.asInstanceOf[OptionMapper2[B1, B2, BR, Option[B1], P2, Option[BR]]]
    implicit def getOptionMapper2OO[B1, B2 <: Enum[B2]: BaseTypedType, P2 <: B2, BR]
      : OptionMapper2[B1, B2, BR, Option[B1], Option[P2], Option[BR]] =
      OptionMapper2.option.asInstanceOf[OptionMapper2[B1, B2, BR, Option[B1], Option[P2], Option[BR]]]
  }
}

trait PostgresProfile extends ExPostgresProfile with SlickEnumSupport with PgArraySupport {
  def pgjson = "jsonb"

  object MyAPI extends ExtPostgresAPI with ArrayImplicits with SlickEnumApi {
    implicit val strListTypeMapper: BaseColumnType[List[String]] =
      new SimpleArrayJdbcType[String]("text").to(_.toList)

    implicit val setPostgresStringArray: SetParameter[Seq[String]] =
      new SetParameter[Seq[String]] {
        def apply(seq: Seq[String], pp: PositionedParameters): Unit = {
          pp.setObject(seq.toArray, java.sql.Types.ARRAY)
        }
      }

    implicit val setPostgresLongArray: SetParameter[Seq[Long]] = new SetParameter[Seq[Long]] {
      def apply(seq: Seq[Long], pp: PositionedParameters): Unit = {
        val array = pp.ps.getConnection.createArrayOf("bigint", seq.toArray)
        pp.pos += 1
        pp.ps.setArray(pp.pos, array)
      }
    }

    def makeSql(sqls: SQLActionBuilder*): SQLActionBuilder = {
      sqls.tail.foldLeft(sqls.head) { case (cumulative, next) =>
        cumulative.concat(sql" ").concat(next)
      }
    }

    def joinSqls(sqls: Seq[SQLActionBuilder], delim: SQLActionBuilder): SQLActionBuilder = {
      sqls.tail.foldLeft(sqls.head) { case (cumulative, next) =>
        cumulative.concat(sql" ").concat(delim).concat(sql" ").concat(next)
      }
    }

    implicit val setInstant: SetParameter[Instant] = new SetParameter[Instant] {
      def apply(i: Instant, pp: PositionedParameters): Unit = {
        pp.pos += 1
        pp.ps.setObject(pp.pos, OffsetDateTime.ofInstant(i, ZoneId.of("UTC")))
      }
    }

    implicit val getInstantResult: GetResult[Instant] = new GetResult[Instant] {
      def apply(p: PositionedResult): Instant = {
        getInstantOptResult(p).get
      }
    }

    implicit val getInstantOptResult: GetResult[Option[Instant]] = new GetResult[Option[Instant]] {
      def apply(p: PositionedResult): Option[Instant] = {
        val result = Option(p.skip.rs.getObject(p.currentPos, classOf[OffsetDateTime])).map(_.toInstant)

        if (p.wasNull()) {
          None
        } else {
          result
        }
      }
    }
  }

  override val api: MyAPI.type = MyAPI
}

object PostgresProfile extends PostgresProfile
