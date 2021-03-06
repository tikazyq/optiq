/*
// Licensed to Julian Hyde under one or more contributor license
// agreements. See the NOTICE file distributed with this work for
// additional information regarding copyright ownership.
//
// Julian Hyde licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except in
// compliance with the License. You may obtain a copy of the License at:
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
*/
package net.hydromatic.optiq.impl.jdbc;

import net.hydromatic.linq4j.expressions.*;

import net.hydromatic.optiq.BuiltinMethod;
import net.hydromatic.optiq.Schemas;
import net.hydromatic.optiq.impl.java.JavaTypeFactory;
import net.hydromatic.optiq.prepare.OptiqPrepareImpl;
import net.hydromatic.optiq.rules.java.*;
import net.hydromatic.optiq.runtime.Hook;
import net.hydromatic.optiq.runtime.SqlFunctions;

import org.eigenbase.rel.RelNode;
import org.eigenbase.rel.convert.ConverterRelImpl;
import org.eigenbase.relopt.*;
import org.eigenbase.reltype.RelDataType;
import org.eigenbase.sql.SqlDialect;
import org.eigenbase.sql.type.SqlTypeName;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * Relational expression representing a scan of a table in a JDBC data source.
 */
public class JdbcToEnumerableConverter
    extends ConverterRelImpl
    implements EnumerableRel {
  protected JdbcToEnumerableConverter(
      RelOptCluster cluster,
      RelTraitSet traits,
      RelNode input) {
    super(cluster, ConventionTraitDef.INSTANCE, traits, input);
  }

  @Override
  public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    return new JdbcToEnumerableConverter(
        getCluster(), traitSet, sole(inputs));
  }

  @Override
  public RelOptCost computeSelfCost(RelOptPlanner planner) {
    return super.computeSelfCost(planner).multiplyBy(.1);
  }

  public Result implement(EnumerableRelImplementor implementor, Prefer pref) {
    // Generate:
    //   ResultSetEnumerable.of(schema.getDataSource(), "select ...")
    final BlockBuilder builder0 = new BlockBuilder(false);
    final JdbcRel child = (JdbcRel) getChild();
    final PhysType physType =
        PhysTypeImpl.of(
            implementor.getTypeFactory(), getRowType(),
            pref.prefer(JavaRowFormat.CUSTOM));
    final JdbcConvention jdbcConvention =
        (JdbcConvention) child.getConvention();
    String sql = generateSql(jdbcConvention.jdbcSchema.dialect);
    if (OptiqPrepareImpl.DEBUG) {
      System.out.println("[" + sql + "]");
    }
    Hook.QUERY_PLAN.run(sql);
    final Expression sql_ =
        builder0.append("sql", Expressions.constant(sql));
    final int fieldCount = getRowType().getFieldCount();
    BlockBuilder builder = new BlockBuilder();
    final ParameterExpression resultSet_ =
        Expressions.parameter(Modifier.FINAL, ResultSet.class,
            builder.newName("resultSet"));
    CalendarPolicy calendarPolicy =
        CalendarPolicy.of(jdbcConvention.jdbcSchema.dialect);
    final Expression calendar_;
    switch (calendarPolicy) {
    case LOCAL:
      calendar_ =
          builder0.append("calendar",
              Expressions.call(Calendar.class, "getInstance",
                  getTimeZoneExpression(implementor)));
      break;
    default:
      calendar_ = null;
    }
    if (fieldCount == 1) {
      final ParameterExpression value_ =
          Expressions.parameter(Object.class, builder.newName("value"));
      builder.add(Expressions.declare(0, value_, null));
      generateGet(implementor, physType, builder, resultSet_, 0, value_,
          calendar_, calendarPolicy);
      builder.add(Expressions.return_(null, value_));
    } else {
      final Expression values_ =
          builder.append("values",
              Expressions.newArrayBounds(Object.class, 1,
                  Expressions.constant(fieldCount)));
      for (int i = 0; i < fieldCount; i++) {
        generateGet(implementor, physType, builder, resultSet_, i,
            Expressions.arrayIndex(values_, Expressions.constant(i)),
            calendar_, calendarPolicy);
      }
      builder.add(
          Expressions.return_(null, values_));
    }
    final ParameterExpression e_ =
        Expressions.parameter(SQLException.class, builder.newName("e"));
    final Expression rowBuilderFactory_ =
        builder0.append("rowBuilderFactory",
            Expressions.lambda(
                Expressions.block(
                    Expressions.return_(null,
                        Expressions.lambda(
                            Expressions.block(
                                Expressions.tryCatch(
                                    builder.toBlock(),
                                    Expressions.catch_(
                                        e_,
                                        Expressions.throw_(
                                            Expressions.new_(
                                                RuntimeException.class,
                                                e_)))))))),
                resultSet_));
    final Expression enumerable =
        builder0.append(
            "enumerable",
            Expressions.call(
                BuiltinMethod.RESULT_SET_ENUMERABLE_OF.method,
                Expressions.call(
                    Schemas.unwrap(jdbcConvention.jdbcSchema.getExpression(),
                        JdbcSchema.class),
                    BuiltinMethod.JDBC_SCHEMA_DATA_SOURCE.method),
                sql_,
                rowBuilderFactory_));
    builder0.add(
        Expressions.return_(null, enumerable));
    return implementor.result(physType, builder0.toBlock());
  }

  private UnaryExpression getTimeZoneExpression(
      EnumerableRelImplementor implementor) {
    return Expressions.convert_(
        Expressions.call(
            implementor.getRootExpression(),
            "get",
            Expressions.constant("timeZone")),
        TimeZone.class);
  }

  private void generateGet(EnumerableRelImplementor implementor,
      PhysType physType, BlockBuilder builder, ParameterExpression resultSet_,
      int i, Expression target, Expression calendar_,
      CalendarPolicy calendarPolicy) {
    final Primitive primitive = Primitive.ofBoxOr(physType.fieldClass(i));
    final RelDataType fieldType =
        physType.getRowType().getFieldList().get(i).getType();
    final List<Expression> dateTimeArgs = new ArrayList<Expression>();
    dateTimeArgs.add(Expressions.constant(i + 1));
    SqlTypeName sqlTypeName = fieldType.getSqlTypeName();
    boolean offset = false;
    switch (calendarPolicy) {
    case LOCAL:
      dateTimeArgs.add(calendar_);
      break;
    case NULL:
      dateTimeArgs.add(Expressions.constant(null));
      break;
    case DIRECT:
      sqlTypeName = SqlTypeName.ANY;
      break;
    case SHIFT:
      switch (sqlTypeName) {
      case TIMESTAMP:
      case DATE:
        offset = true;
      }
      break;
    }
    final Expression source;
    switch (sqlTypeName) {
    case DATE:
    case TIME:
    case TIMESTAMP:
      source = Expressions.call(
          getMethod(sqlTypeName, fieldType.isNullable(), offset),
          Expressions.<Expression>list()
              .append(
                  Expressions.call(resultSet_,
                      getMethod2(sqlTypeName), dateTimeArgs))
          .appendIf(offset, getTimeZoneExpression(implementor)));
      break;
    default:
      source = Expressions.call(
          resultSet_, jdbcGetMethod(primitive), Expressions.constant(i + 1));
    }
    builder.add(
        Expressions.statement(
            Expressions.assign(
                target, source)));
  }

  private Method getMethod(SqlTypeName sqlTypeName, boolean nullable,
      boolean offset) {
    switch (sqlTypeName) {
    case DATE:
      return (nullable
          ? BuiltinMethod.DATE_TO_INT_OPTIONAL
          : BuiltinMethod.DATE_TO_INT).method;
    case TIME:
      return (nullable
          ? BuiltinMethod.TIME_TO_INT_OPTIONAL
          : BuiltinMethod.TIME_TO_INT).method;
    case TIMESTAMP:
      return (nullable
          ? (offset
          ? BuiltinMethod.TIMESTAMP_TO_LONG_OPTIONAL_OFFSET
          : BuiltinMethod.TIMESTAMP_TO_LONG_OPTIONAL)
          : (offset
              ? BuiltinMethod.TIMESTAMP_TO_LONG_OFFSET
              : BuiltinMethod.TIMESTAMP_TO_LONG)).method;
    default:
      throw new AssertionError(sqlTypeName + ":" + nullable);
    }
  }

  private Method getMethod2(SqlTypeName sqlTypeName) {
    switch (sqlTypeName) {
    case DATE:
      return BuiltinMethod.RESULT_SET_GET_DATE2.method;
    case TIME:
      return BuiltinMethod.RESULT_SET_GET_TIME2.method;
    case TIMESTAMP:
      return BuiltinMethod.RESULT_SET_GET_TIMESTAMP2.method;
    default:
      throw new AssertionError(sqlTypeName);
    }
  }

  /** E,g, {@code jdbcGetMethod(int)} returns "getInt". */
  private String jdbcGetMethod(Primitive primitive) {
    return primitive == null
        ? "getObject"
        : "get" + SqlFunctions.initcap(primitive.primitiveName);
  }

  private String generateSql(SqlDialect dialect) {
    final JdbcImplementor jdbcImplementor =
        new JdbcImplementor(dialect,
            (JavaTypeFactory) getCluster().getTypeFactory());
    final JdbcImplementor.Result result =
        jdbcImplementor.visitChild(0, getChild());
    return result.asQuery().toSqlString(dialect).getSql();
  }

  /** Whether this JDBC driver needs you to pass a Calendar object to methods
   * such as {@link ResultSet#getTimestamp(int, java.util.Calendar)}. */
  private enum CalendarPolicy {
    NONE,
    NULL,
    LOCAL,
    DIRECT,
    SHIFT;

    static CalendarPolicy of(SqlDialect dialect) {
      switch (dialect.getDatabaseProduct()) {
      case MYSQL:
        return SHIFT;
      case HSQLDB:
      default:
        // NULL works for hsqldb-2.3; nothing worked for hsqldb-1.8.
        return NULL;
      }
    }
  }
}

// End JdbcToEnumerableConverter.java
