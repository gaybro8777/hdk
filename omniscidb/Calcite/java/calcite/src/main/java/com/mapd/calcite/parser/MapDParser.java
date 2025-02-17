/*
 * Copyright 2021 OmniSci, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mapd.calcite.parser;

import static org.apache.calcite.sql.parser.SqlParserPos.ZERO;

import com.google.common.collect.ImmutableList;
import com.mapd.metadata.MetaConnect;
import com.mapd.parser.extension.ddl.ExtendedSqlParser;
import com.mapd.parser.extension.ddl.JsonSerializableDdl;
import com.mapd.parser.hint.OmniSciHintStrategyTable;

import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.config.CalciteConnectionProperty;
import org.apache.calcite.plan.Context;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.prepare.MapDPlanner;
import org.apache.calcite.prepare.SqlIdentifierCapturer;
import org.apache.calcite.rel.RelHomogeneousShuttle;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.RelShuttleImpl;
import org.apache.calcite.rel.RelVisitor;
import org.apache.calcite.rel.core.Correlate;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.logical.LogicalSort;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rex.*;
import org.apache.calcite.runtime.CalciteException;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.dialect.CalciteSqlDialect;
import org.apache.calcite.sql.fun.SqlCase;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.sql.util.SqlBasicVisitor;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.tools.*;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 *
 * @author michael
 */
public final class MapDParser {
  public static final ThreadLocal<MapDParser> CURRENT_PARSER = new ThreadLocal<>();

  final static Logger MAPDLOGGER = LoggerFactory.getLogger(MapDParser.class);

  private final Supplier<MapDSqlOperatorTable> mapDSqlOperatorTable;

  private int callCount = 0;
  private MapDUser mapdUser;
  private String schemaJson;

  public MapDParser(final Supplier<MapDSqlOperatorTable> mapDSqlOperatorTable) {
    this.mapDSqlOperatorTable = mapDSqlOperatorTable;
    this.schemaJson = "{}";
  }

  private static final Context MAPD_CONNECTION_CONTEXT = new Context() {
    MapDTypeSystem myTypeSystem = new MapDTypeSystem();
    CalciteConnectionConfig config = new CalciteConnectionConfigImpl(new Properties()) {
      {
        properties.put(CalciteConnectionProperty.CASE_SENSITIVE.camelName(),
                String.valueOf(false));
        properties.put(CalciteConnectionProperty.CONFORMANCE.camelName(),
                String.valueOf(SqlConformanceEnum.LENIENT));
      }

      @SuppressWarnings("unchecked")
      public <T extends Object> T typeSystem(
              java.lang.Class<T> typeSystemClass, T defaultTypeSystem) {
        return (T) myTypeSystem;
      };

      public boolean caseSensitive() {
        return false;
      };

      public org.apache.calcite.sql.validate.SqlConformance conformance() {
        return SqlConformanceEnum.LENIENT;
      };
    };

    @Override
    public <C> C unwrap(Class<C> aClass) {
      if (aClass.isInstance(config)) {
        return aClass.cast(config);
      }
      return null;
    }
  };

  private MapDPlanner getPlanner() {
    return getPlanner(false, false);
  }

  private MapDPlanner getPlanner(
          final boolean allowSubQueryExpansion, final boolean isWatchdogEnabled) {
    // create the default schema
    final SchemaPlus rootSchema = Frameworks.createRootSchema(true);
    final MapDSchema defaultSchema = new MapDSchema(this, mapdUser, null, schemaJson);
    final SchemaPlus defaultSchemaPlus = rootSchema.add(mapdUser.getDB(), defaultSchema);

    // add the other potential schemas
    // this is where the systyem schema would be added
    final MetaConnect mc = new MetaConnect(mapdUser, this);

    // TODO MAT for this checkin we are not going to actually allow any additional
    // schemas
    // Eveything should work and perform as it ever did
    if (false) {
      for (String db : mc.getDatabases()) {
        if (!db.toUpperCase().equals(mapdUser.getDB().toUpperCase())) {
          rootSchema.add(db, new MapDSchema(this, mapdUser, db));
        }
      }
    }

    final FrameworkConfig config =
            Frameworks.newConfigBuilder()
                    .defaultSchema(defaultSchemaPlus)
                    .operatorTable(mapDSqlOperatorTable.get())
                    .parserConfig(SqlParser.config()
                                          .withConformance(SqlConformanceEnum.LENIENT)
                                          .withUnquotedCasing(Casing.UNCHANGED)
                                          .withCaseSensitive(false)
                                          // allow identifiers of up to 512 chars
                                          .withIdentifierMaxLength(512)
                                          .withParserFactory(ExtendedSqlParser.FACTORY))
                    .sqlToRelConverterConfig(
                            SqlToRelConverter.config()
                                    .withExpand(allowSubQueryExpansion)
                                    .withDecorrelationEnabled(true) // this is default
                                    // allow as many as possible IN operator values
                                    .withInSubQueryThreshold(Integer.MAX_VALUE)
                                    .withHintStrategyTable(
                                            OmniSciHintStrategyTable.HINT_STRATEGY_TABLE)
                                    .addRelBuilderConfigTransform(c
                                            -> c.withPruneInputOfAggregate(false)
                                                       .withSimplify(false)))
                    .typeSystem(createTypeSystem())
                    .context(MAPD_CONNECTION_CONTEXT)
                    .build();
    MapDPlanner planner = new MapDPlanner(config);
    planner.setRestriction(mapdUser.getRestriction());
    return planner;
  }

  public void setUser(MapDUser mapdUser) {
    this.mapdUser = mapdUser;
  }

  public void setSchema(String schemaJson) {
    this.schemaJson = schemaJson;
  }

  public Pair<String, SqlIdentifierCapturer> process(
          String sql, final MapDParserOptions parserOptions)
          throws SqlParseException, ValidationException, RelConversionException {
    final MapDPlanner planner = getPlanner(false, parserOptions.isWatchdogEnabled());
    final SqlNode sqlNode = parseSql(sql, parserOptions.isLegacySyntax(), planner);
    String res = processSql(sqlNode, parserOptions);
    SqlIdentifierCapturer capture = captureIdentifiers(sqlNode);
    return new Pair<String, SqlIdentifierCapturer>(res, capture);
  }

  public String optimizeRAQuery(String query, final MapDParserOptions parserOptions)
          throws IOException {
    MapDSchema schema = new MapDSchema(this, mapdUser, null, schemaJson);
    MapDPlanner planner = getPlanner(false, parserOptions.isWatchdogEnabled());

    planner.setFilterPushDownInfo(parserOptions.getFilterPushDownInfo());
    RelRoot optRel = planner.optimizeRaQuery(query, schema);
    optRel = replaceIsTrue(planner.getTypeFactory(), optRel);
    return MapDSerializer.toString(optRel.project());
  }

  public String processSql(String sql, final MapDParserOptions parserOptions)
          throws SqlParseException, ValidationException, RelConversionException {
    callCount++;

    final MapDPlanner planner = getPlanner(false, parserOptions.isWatchdogEnabled());
    final SqlNode sqlNode = parseSql(sql, parserOptions.isLegacySyntax(), planner);

    return processSql(sqlNode, parserOptions);
  }

  public String processSql(final SqlNode sqlNode, final MapDParserOptions parserOptions)
          throws SqlParseException, ValidationException, RelConversionException {
    callCount++;

    if (sqlNode instanceof JsonSerializableDdl) {
      return ((JsonSerializableDdl) sqlNode).toJsonString();
    }

    if (sqlNode instanceof SqlDdl) {
      return sqlNode.toString();
    }

    final MapDPlanner planner = getPlanner(false, parserOptions.isWatchdogEnabled());
    planner.advanceToValidate();

    final RelRoot sqlRel = convertSqlToRelNode(sqlNode, planner, parserOptions);
    RelNode project = sqlRel.project();

    if (parserOptions.isExplain()) {
      return RelOptUtil.toString(sqlRel.project());
    }

    String res = MapDSerializer.toString(project);

    return res;
  }

  public MapDPlanner.CompletionResult getCompletionHints(
          String sql, int cursor, List<String> visible_tables) {
    return getPlanner().getCompletionHints(sql, cursor, visible_tables);
  }

  public HashSet<ImmutableList<String>> resolveSelectIdentifiers(
          SqlIdentifierCapturer capturer) {
    MapDSchema schema = new MapDSchema(this, mapdUser, null, schemaJson);
    HashSet<ImmutableList<String>> resolved = new HashSet<ImmutableList<String>>();

    for (ImmutableList<String> names : capturer.selects) {
      MapDTable table = (MapDTable) schema.getTable(names.get(0));
      if (null == table) {
        throw new RuntimeException("table/view not found: " + names.get(0));
      }
      resolved.add(names);
    }

    return resolved;
  }

  private static class SqlHavingVisitor extends SqlBasicVisitor<Void> {
    boolean hasHaving = false;

    @Override
    public Void visit(SqlCall call) {
      if (call instanceof SqlSelect) {
        final SqlSelect select = (SqlSelect) call;
        if (select.getHaving() != null) {
          this.hasHaving = true;
        }
      }
      return call.getOperator().acceptCall(this, call);
    }
  }

  private static class SqlNotInVisitor extends SqlBasicVisitor<Void> {
    boolean hasNotIn = false;

    @Override
    public Void visit(SqlCall call) {
      if (call instanceof SqlSelect) {
        final SqlSelect select = (SqlSelect) call;
        final SqlNode whereNode = select.getWhere();
        if (whereNode != null && whereNode instanceof SqlCall) {
          final SqlCall whereCall = (SqlCall) whereNode;
          assert (whereCall != null);
          final SqlOperator op = whereCall.getOperator();
          if (op.getKind() == SqlKind.NOT_IN) {
            hasNotIn = true;
          }
        }
      }

      return call.getOperator().acceptCall(this, call);
    }
  }

  private static class SubqueryExpansionRelVisitor extends RelHomogeneousShuttle {
    private final SubqueryExpansionRexVisitor subqueryRexVisitor;
    private boolean containsSort = false;

    public SubqueryExpansionRelVisitor() {
      subqueryRexVisitor = new SubqueryExpansionRexVisitor(this);
    }

    @Override
    public RelNode visit(RelNode other) {
      RelNode next = super.visit(other);
      return next.accept(subqueryRexVisitor);
    }

    @Override
    public RelNode visit(LogicalSort sort) {
      containsSort = true;
      RelNode next = super.visit(sort);
      return next.accept(subqueryRexVisitor);
    }

    public boolean requiresSubqueryExpansion() {
      if (subqueryRexVisitor != null) {
        return subqueryRexVisitor.requiresSubqueryExpansion;
      }
      return false;
    }

    private static class SubqueryExpansionRexVisitor extends RexShuttle {
      private boolean requiresSubqueryExpansion = false;
      private final SubqueryExpansionRelVisitor shuttle;

      SubqueryExpansionRexVisitor(SubqueryExpansionRelVisitor shuttle) {
        this.shuttle = shuttle;
      }

      @Override
      public RexNode visitCorrelVariable(RexCorrelVariable variable) {
        requiresSubqueryExpansion = true;
        return variable;
      }

      @Override
      public RexNode visitSubQuery(RexSubQuery subQuery) {
        if (shuttle != null) {
          if (subQuery.getKind() == SqlKind.EXISTS) {
            requiresSubqueryExpansion = true;
          }
          if (subQuery.getKind() == SqlKind.IN) {
            requiresSubqueryExpansion = true;
          }
          RelNode r = subQuery.rel.accept(shuttle); // look inside sub-queries
          if (r != subQuery.rel) {
            subQuery = subQuery.clone(r);
          }
        }
        return super.visitSubQuery(subQuery);
      }
    }
  }

  RelRoot convertSqlToRelNode(final SqlNode sqlNode,
          final MapDPlanner mapDPlanner,
          final MapDParserOptions parserOptions)
          throws SqlParseException, ValidationException, RelConversionException {
    SqlNode node = sqlNode;
    MapDPlanner planner = mapDPlanner;
    boolean allowCorrelatedSubQueryExpansion = false;
    boolean expandOverride = true;

    SqlHavingVisitor hasHavingVisitor = new SqlHavingVisitor();
    node.accept(hasHavingVisitor);

    if (hasHavingVisitor.hasHaving) {
      allowCorrelatedSubQueryExpansion = true; // TODO: is this doing anything?
    }

    SqlNotInVisitor hasNotInVisitor = new SqlNotInVisitor();
    node.accept(hasNotInVisitor);
    if (hasNotInVisitor.hasNotIn) {
      expandOverride = false;
    }

    SqlNode validateR;
    // sometimes validation fails due to optimizations applied to other parts of the
    // query. Run a cleanup pass here in case validate fails, disabling legacy syntax and
    // rebuilding the RA tree from the Sql.
    try {
      validateR = planner.validate(node);
    } catch (Exception e) {
      // close original planner
      planner.close();
      // create a new one
      planner = getPlanner(
              allowCorrelatedSubQueryExpansion, parserOptions.isWatchdogEnabled());
      node = parseSql(
              node.toSqlString(CalciteSqlDialect.DEFAULT).toString(), false, planner);
      validateR = planner.validate(node);
    }
    planner.setFilterPushDownInfo(parserOptions.getFilterPushDownInfo());
    RelRoot relR = planner.rel(validateR);

    final SubqueryExpansionRelVisitor visitor = new SubqueryExpansionRelVisitor();
    relR.project().accept(visitor);

    final boolean requiresSubqueryExpansion = visitor.requiresSubqueryExpansion();
    final boolean hasSort = visitor.containsSort;

    if (expandOverride && requiresSubqueryExpansion) {
      planner.close(); // replace planner
      allowCorrelatedSubQueryExpansion = true;
      planner = getPlanner(
              allowCorrelatedSubQueryExpansion, parserOptions.isWatchdogEnabled());
      if (hasSort) {
        if (node instanceof SqlOrderBy) {
          SqlOrderBy order_by_node = (SqlOrderBy) node;
          if (order_by_node.query instanceof SqlSelect) {
            SqlNodeList baseOrderList = order_by_node.orderList;
            SqlNodeList orderList = ((SqlSelect) order_by_node.query).getOrderList();
            if (baseOrderList.size() != orderList.size()) {
              throw new CalciteException("Correlated sub-query with sort not supported. "
                              + baseOrderList.size() + " vs " + orderList.size(),
                      null);
            }
            for (int i = 0; i < baseOrderList.size(); i++) {
              if (baseOrderList.get(i) != orderList.get(i)) {
                throw new CalciteException(
                        "Correlated sub-query with sort not supported.", null);
              }
            }
            // drop duplicate order by
            node = order_by_node.query;
          }
        }
      }
      node = parseSql(
              node.toSqlString(CalciteSqlDialect.DEFAULT).toString(), false, planner);
      validateR = planner.validate(node);

      planner.setFilterPushDownInfo(parserOptions.getFilterPushDownInfo());
      relR = planner.rel(validateR);
    }

    relR = replaceIsTrue(planner.getTypeFactory(), relR);
    planner.close();

    if (!parserOptions.isViewOptimizeEnabled()) {
      return relR;
    } else {
      // check to see if a view is involved in the query
      MapDSchema schema = new MapDSchema(this, mapdUser, null, schemaJson);
      SqlIdentifierCapturer capturer = captureIdentifiers(sqlNode);
      for (ImmutableList<String> names : capturer.selects) {
        MapDTable table = (MapDTable) schema.getTable(names.get(0));
        if (null == table) {
          throw new RuntimeException("table/view not found: " + names.get(0));
        }
      }

      return relR;
    }
  }

  private RelRoot replaceIsTrue(final RelDataTypeFactory typeFactory, RelRoot root) {
    final RexShuttle callShuttle = new RexShuttle() {
      RexBuilder builder = new RexBuilder(typeFactory);

      public RexNode visitCall(RexCall call) {
        call = (RexCall) super.visitCall(call);
        if (call.getKind() == SqlKind.IS_TRUE) {
          return builder.makeCall(SqlStdOperatorTable.AND,
                  builder.makeCall(
                          SqlStdOperatorTable.IS_NOT_NULL, call.getOperands().get(0)),
                  call.getOperands().get(0));
        } else if (call.getKind() == SqlKind.IS_NOT_TRUE) {
          return builder.makeCall(SqlStdOperatorTable.OR,
                  builder.makeCall(
                          SqlStdOperatorTable.IS_NULL, call.getOperands().get(0)),
                  builder.makeCall(SqlStdOperatorTable.NOT, call.getOperands().get(0)));
        } else if (call.getKind() == SqlKind.IS_FALSE) {
          return builder.makeCall(SqlStdOperatorTable.AND,
                  builder.makeCall(
                          SqlStdOperatorTable.IS_NOT_NULL, call.getOperands().get(0)),
                  builder.makeCall(SqlStdOperatorTable.NOT, call.getOperands().get(0)));
        } else if (call.getKind() == SqlKind.IS_NOT_FALSE) {
          return builder.makeCall(SqlStdOperatorTable.OR,
                  builder.makeCall(
                          SqlStdOperatorTable.IS_NULL, call.getOperands().get(0)),
                  call.getOperands().get(0));
        }

        return call;
      }
    };

    RelNode node = root.rel.accept(new RelShuttleImpl() {
      @Override
      protected RelNode visitChild(RelNode parent, int i, RelNode child) {
        RelNode node = super.visitChild(parent, i, child);
        return node.accept(callShuttle);
      }
    });

    return new RelRoot(node,
            root.validatedRowType,
            root.kind,
            root.fields,
            root.collation,
            Collections.emptyList());
  }

  private SqlNode parseSql(String sql, final boolean legacy_syntax, Planner planner)
          throws SqlParseException {
    SqlNode parseR = null;
    try {
      parseR = planner.parse(sql);
      MAPDLOGGER.debug(" node is \n" + parseR.toString());
    } catch (SqlParseException ex) {
      MAPDLOGGER.error("failed to parse SQL '" + sql + "' \n" + ex.toString());
      throw ex;
    }

    if (!legacy_syntax) {
      return parseR;
    }

    RelDataTypeFactory typeFactory = planner.getTypeFactory();
    SqlSelect select_node = null;
    if (parseR instanceof SqlSelect) {
      select_node = (SqlSelect) parseR;
      desugar(select_node, typeFactory);
    } else if (parseR instanceof SqlOrderBy) {
      SqlOrderBy order_by_node = (SqlOrderBy) parseR;
      if (order_by_node.query instanceof SqlSelect) {
        select_node = (SqlSelect) order_by_node.query;
        SqlOrderBy new_order_by_node = desugar(select_node, order_by_node, typeFactory);
        if (new_order_by_node != null) {
          return new_order_by_node;
        }
      } else if (order_by_node.query instanceof SqlWith) {
        SqlWith old_with_node = (SqlWith) order_by_node.query;
        if (old_with_node.body instanceof SqlSelect) {
          select_node = (SqlSelect) old_with_node.body;
          desugar(select_node, typeFactory);
        }
      }
    } else if (parseR instanceof SqlWith) {
      SqlWith old_with_node = (SqlWith) parseR;
      if (old_with_node.body instanceof SqlSelect) {
        select_node = (SqlSelect) old_with_node.body;
        desugar(select_node, typeFactory);
      }
    }
    return parseR;
  }

  private void desugar(SqlSelect select_node, RelDataTypeFactory typeFactory) {
    desugar(select_node, null, typeFactory);
  }

  private SqlNode expandCase(SqlCase old_case_node, RelDataTypeFactory typeFactory) {
    SqlNodeList newWhenList =
            new SqlNodeList(old_case_node.getWhenOperands().getParserPosition());
    SqlNodeList newThenList =
            new SqlNodeList(old_case_node.getThenOperands().getParserPosition());
    java.util.Map<String, SqlNode> id_to_expr = new java.util.HashMap<String, SqlNode>();
    for (SqlNode node : old_case_node.getWhenOperands()) {
      SqlNode newCall = expand(node, id_to_expr, typeFactory);
      if (null != newCall) {
        newWhenList.add(newCall);
      } else {
        newWhenList.add(node);
      }
    }
    for (SqlNode node : old_case_node.getThenOperands()) {
      SqlNode newCall = expand(node, id_to_expr, typeFactory);
      if (null != newCall) {
        newThenList.add(newCall);
      } else {
        newThenList.add(node);
      }
    }
    SqlNode new_else_operand = old_case_node.getElseOperand();
    if (null != new_else_operand) {
      SqlNode candidate_else_operand =
              expand(old_case_node.getElseOperand(), id_to_expr, typeFactory);
      if (null != candidate_else_operand) {
        new_else_operand = candidate_else_operand;
      }
    }
    SqlNode new_value_operand = old_case_node.getValueOperand();
    if (null != new_value_operand) {
      SqlNode candidate_value_operand =
              expand(old_case_node.getValueOperand(), id_to_expr, typeFactory);
      if (null != candidate_value_operand) {
        new_value_operand = candidate_value_operand;
      }
    }
    SqlNode newCaseNode = SqlCase.createSwitched(old_case_node.getParserPosition(),
            new_value_operand,
            newWhenList,
            newThenList,
            new_else_operand);
    return newCaseNode;
  }

  private SqlOrderBy desugar(SqlSelect select_node,
          SqlOrderBy order_by_node,
          RelDataTypeFactory typeFactory) {
    MAPDLOGGER.debug("desugar: before: " + select_node.toString());
    desugarExpression(select_node.getFrom(), typeFactory);
    desugarExpression(select_node.getWhere(), typeFactory);
    SqlNodeList select_list = select_node.getSelectList();
    SqlNodeList new_select_list = new SqlNodeList(select_list.getParserPosition());
    java.util.Map<String, SqlNode> id_to_expr = new java.util.HashMap<String, SqlNode>();
    for (SqlNode proj : select_list) {
      if (!(proj instanceof SqlBasicCall)) {
        if (proj instanceof SqlCase) {
          new_select_list.add(expandCase((SqlCase) proj, typeFactory));
        } else {
          new_select_list.add(proj);
        }
      } else {
        assert proj instanceof SqlBasicCall;
        SqlBasicCall proj_call = (SqlBasicCall) proj;
        if (proj_call.getOperandList().size() > 0) {
          for (int i = 0; i < proj_call.getOperandList().size(); i++) {
            if (proj_call.operand(i) instanceof SqlCase) {
              SqlNode new_op = expandCase(proj_call.operand(i), typeFactory);
              proj_call.setOperand(i, new_op);
            }
          }
        }
        new_select_list.add(expand(proj_call, id_to_expr, typeFactory));
      }
    }
    select_node.setSelectList(new_select_list);
    SqlNodeList group_by_list = select_node.getGroup();
    if (group_by_list != null) {
      select_node.setGroupBy(expand(group_by_list, id_to_expr, typeFactory));
    }
    SqlNode having = select_node.getHaving();
    if (having != null) {
      expand(having, id_to_expr, typeFactory);
    }
    SqlOrderBy new_order_by_node = null;
    if (order_by_node != null && order_by_node.orderList != null
            && order_by_node.orderList.size() > 0) {
      SqlNodeList new_order_by_list =
              expand(order_by_node.orderList, id_to_expr, typeFactory);
      new_order_by_node = new SqlOrderBy(order_by_node.getParserPosition(),
              select_node,
              new_order_by_list,
              order_by_node.offset,
              order_by_node.fetch);
    }

    MAPDLOGGER.debug("desugar:  after: " + select_node.toString());
    return new_order_by_node;
  }

  private void desugarExpression(SqlNode node, RelDataTypeFactory typeFactory) {
    if (node instanceof SqlSelect) {
      desugar((SqlSelect) node, typeFactory);
      return;
    }
    if (!(node instanceof SqlBasicCall)) {
      return;
    }
    SqlBasicCall basic_call = (SqlBasicCall) node;
    for (SqlNode operator : basic_call.getOperandList()) {
      if (operator instanceof SqlOrderBy) {
        desugarExpression(((SqlOrderBy) operator).query, typeFactory);
      } else {
        desugarExpression(operator, typeFactory);
      }
    }
  }

  private SqlNode expand(final SqlNode node,
          final java.util.Map<String, SqlNode> id_to_expr,
          RelDataTypeFactory typeFactory) {
    MAPDLOGGER.debug("expand: " + node.toString());
    if (node instanceof SqlBasicCall) {
      SqlBasicCall node_call = (SqlBasicCall) node;
      List<SqlNode> operands = node_call.getOperandList();
      for (int i = 0; i < operands.size(); ++i) {
        node_call.setOperand(i, expand(operands.get(i), id_to_expr, typeFactory));
      }
      SqlNode expanded_variance = expandVariance(node_call, typeFactory);
      if (expanded_variance != null) {
        return expanded_variance;
      }
      SqlNode expanded_covariance = expandCovariance(node_call, typeFactory);
      if (expanded_covariance != null) {
        return expanded_covariance;
      }
      SqlNode expanded_correlation = expandCorrelation(node_call, typeFactory);
      if (expanded_correlation != null) {
        return expanded_correlation;
      }
    }
    if (node instanceof SqlSelect) {
      SqlSelect select_node = (SqlSelect) node;
      desugar(select_node, typeFactory);
    }
    return node;
  }

  private SqlNodeList expand(final SqlNodeList group_by_list,
          final java.util.Map<String, SqlNode> id_to_expr,
          RelDataTypeFactory typeFactory) {
    SqlNodeList new_group_by_list = new SqlNodeList(new SqlParserPos(-1, -1));
    for (SqlNode group_by : group_by_list) {
      if (!(group_by instanceof SqlIdentifier)) {
        new_group_by_list.add(expand(group_by, id_to_expr, typeFactory));
        continue;
      }
      SqlIdentifier group_by_id = ((SqlIdentifier) group_by);
      if (id_to_expr.containsKey(group_by_id.toString())) {
        new_group_by_list.add(id_to_expr.get(group_by_id.toString()));
      } else {
        new_group_by_list.add(group_by);
      }
    }
    return new_group_by_list;
  }

  private SqlNode expandVariance(
          final SqlBasicCall proj_call, RelDataTypeFactory typeFactory) {
    // Expand variance aggregates that are not supported natively
    if (proj_call.operandCount() != 1) {
      return null;
    }
    boolean biased;
    boolean sqrt;
    boolean flt;
    if (proj_call.getOperator().isName("STDDEV_POP", false)) {
      biased = true;
      sqrt = true;
      flt = false;
    } else if (proj_call.getOperator().getName().equalsIgnoreCase("STDDEV_POP_FLOAT")) {
      biased = true;
      sqrt = true;
      flt = true;
    } else if (proj_call.getOperator().isName("STDDEV_SAMP", false)
            || proj_call.getOperator().getName().equalsIgnoreCase("STDDEV")) {
      biased = false;
      sqrt = true;
      flt = false;
    } else if (proj_call.getOperator().getName().equalsIgnoreCase("STDDEV_SAMP_FLOAT")
            || proj_call.getOperator().getName().equalsIgnoreCase("STDDEV_FLOAT")) {
      biased = false;
      sqrt = true;
      flt = true;
    } else if (proj_call.getOperator().isName("VAR_POP", false)) {
      biased = true;
      sqrt = false;
      flt = false;
    } else if (proj_call.getOperator().getName().equalsIgnoreCase("VAR_POP_FLOAT")) {
      biased = true;
      sqrt = false;
      flt = true;
    } else if (proj_call.getOperator().isName("VAR_SAMP", false)
            || proj_call.getOperator().getName().equalsIgnoreCase("VARIANCE")) {
      biased = false;
      sqrt = false;
      flt = false;
    } else if (proj_call.getOperator().getName().equalsIgnoreCase("VAR_SAMP_FLOAT")
            || proj_call.getOperator().getName().equalsIgnoreCase("VARIANCE_FLOAT")) {
      biased = false;
      sqrt = false;
      flt = true;
    } else {
      return null;
    }
    final SqlNode operand = proj_call.operand(0);
    final SqlParserPos pos = proj_call.getParserPosition();
    SqlNode expanded_proj_call =
            expandVariance(pos, operand, biased, sqrt, flt, typeFactory);
    MAPDLOGGER.debug("Expanded select_list SqlCall: " + proj_call.toString());
    MAPDLOGGER.debug("to : " + expanded_proj_call.toString());
    return expanded_proj_call;
  }

  private SqlNode expandVariance(final SqlParserPos pos,
          final SqlNode operand,
          boolean biased,
          boolean sqrt,
          boolean flt,
          RelDataTypeFactory typeFactory) {
    // stddev_pop(x) ==>
    // power(
    // (sum(x * x) - sum(x) * sum(x) / (case count(x) when 0 then NULL else count(x)
    // end)) / (case count(x) when 0 then NULL else count(x) end), .5)
    //
    // stddev_samp(x) ==>
    // power(
    // (sum(x * x) - sum(x) * sum(x) / (case count(x) when 0 then NULL else count(x)
    // )) / ((case count(x) when 1 then NULL else count(x) - 1 end)), .5)
    //
    // var_pop(x) ==>
    // (sum(x * x) - sum(x) * sum(x) / ((case count(x) when 0 then NULL else
    // count(x)
    // end))) / ((case count(x) when 0 then NULL else count(x) end))
    //
    // var_samp(x) ==>
    // (sum(x * x) - sum(x) * sum(x) / ((case count(x) when 0 then NULL else
    // count(x)
    // end))) / ((case count(x) when 1 then NULL else count(x) - 1 end))
    //
    final SqlNode arg = SqlStdOperatorTable.CAST.createCall(pos,
            operand,
            SqlTypeUtil.convertTypeToSpec(typeFactory.createSqlType(
                    flt ? SqlTypeName.FLOAT : SqlTypeName.DOUBLE)));
    final SqlNode argSquared = SqlStdOperatorTable.MULTIPLY.createCall(pos, arg, arg);
    final SqlNode sumArgSquared = SqlStdOperatorTable.SUM.createCall(pos, argSquared);
    final SqlNode sum = SqlStdOperatorTable.SUM.createCall(pos, arg);
    final SqlNode sumSquared = SqlStdOperatorTable.MULTIPLY.createCall(pos, sum, sum);
    final SqlNode count = SqlStdOperatorTable.COUNT.createCall(pos, arg);
    final SqlLiteral nul = SqlLiteral.createNull(pos);
    final SqlNumericLiteral zero = SqlLiteral.createExactNumeric("0", pos);
    final SqlNode countEqZero = SqlStdOperatorTable.EQUALS.createCall(pos, count, zero);
    SqlNodeList whenList = new SqlNodeList(pos);
    SqlNodeList thenList = new SqlNodeList(pos);
    whenList.add(countEqZero);
    thenList.add(nul);
    final SqlNode int_denominator = SqlStdOperatorTable.CASE.createCall(
            null, pos, null, whenList, thenList, count);
    final SqlNode denominator = SqlStdOperatorTable.CAST.createCall(pos,
            int_denominator,
            SqlTypeUtil.convertTypeToSpec(typeFactory.createSqlType(
                    flt ? SqlTypeName.FLOAT : SqlTypeName.DOUBLE)));
    final SqlNode avgSumSquared =
            SqlStdOperatorTable.DIVIDE.createCall(pos, sumSquared, denominator);
    final SqlNode diff =
            SqlStdOperatorTable.MINUS.createCall(pos, sumArgSquared, avgSumSquared);
    final SqlNode denominator1;
    if (biased) {
      denominator1 = denominator;
    } else {
      final SqlNumericLiteral one = SqlLiteral.createExactNumeric("1", pos);
      final SqlNode countEqOne = SqlStdOperatorTable.EQUALS.createCall(pos, count, one);
      final SqlNode countMinusOne = SqlStdOperatorTable.MINUS.createCall(pos, count, one);
      SqlNodeList whenList1 = new SqlNodeList(pos);
      SqlNodeList thenList1 = new SqlNodeList(pos);
      whenList1.add(countEqOne);
      thenList1.add(nul);
      final SqlNode int_denominator1 = SqlStdOperatorTable.CASE.createCall(
              null, pos, null, whenList1, thenList1, countMinusOne);
      denominator1 = SqlStdOperatorTable.CAST.createCall(pos,
              int_denominator1,
              SqlTypeUtil.convertTypeToSpec(typeFactory.createSqlType(
                      flt ? SqlTypeName.FLOAT : SqlTypeName.DOUBLE)));
    }
    final SqlNode div = SqlStdOperatorTable.DIVIDE.createCall(pos, diff, denominator1);
    SqlNode result = div;
    if (sqrt) {
      final SqlNumericLiteral half = SqlLiteral.createExactNumeric("0.5", pos);
      result = SqlStdOperatorTable.POWER.createCall(pos, div, half);
    }
    return SqlStdOperatorTable.CAST.createCall(pos,
            result,
            SqlTypeUtil.convertTypeToSpec(typeFactory.createSqlType(
                    flt ? SqlTypeName.FLOAT : SqlTypeName.DOUBLE)));
  }

  private SqlNode expandCovariance(
          final SqlBasicCall proj_call, RelDataTypeFactory typeFactory) {
    // Expand covariance aggregates
    if (proj_call.operandCount() != 2) {
      return null;
    }
    boolean pop;
    boolean flt;
    if (proj_call.getOperator().isName("COVAR_POP", false)) {
      pop = true;
      flt = false;
    } else if (proj_call.getOperator().isName("COVAR_SAMP", false)) {
      pop = false;
      flt = false;
    } else if (proj_call.getOperator().getName().equalsIgnoreCase("COVAR_POP_FLOAT")) {
      pop = true;
      flt = true;
    } else if (proj_call.getOperator().getName().equalsIgnoreCase("COVAR_SAMP_FLOAT")) {
      pop = false;
      flt = true;
    } else {
      return null;
    }
    final SqlNode operand0 = proj_call.operand(0);
    final SqlNode operand1 = proj_call.operand(1);
    final SqlParserPos pos = proj_call.getParserPosition();
    SqlNode expanded_proj_call =
            expandCovariance(pos, operand0, operand1, pop, flt, typeFactory);
    MAPDLOGGER.debug("Expanded select_list SqlCall: " + proj_call.toString());
    MAPDLOGGER.debug("to : " + expanded_proj_call.toString());
    return expanded_proj_call;
  }

  private SqlNode expandCovariance(SqlParserPos pos,
          final SqlNode operand0,
          final SqlNode operand1,
          boolean pop,
          boolean flt,
          RelDataTypeFactory typeFactory) {
    // covar_pop(x, y) ==> avg(x * y) - avg(x) * avg(y)
    // covar_samp(x, y) ==> (sum(x * y) - sum(x) * avg(y))
    // ((case count(x) when 1 then NULL else count(x) - 1 end))
    final SqlNode arg0 = SqlStdOperatorTable.CAST.createCall(operand0.getParserPosition(),
            operand0,
            SqlTypeUtil.convertTypeToSpec(typeFactory.createSqlType(
                    flt ? SqlTypeName.FLOAT : SqlTypeName.DOUBLE)));
    final SqlNode arg1 = SqlStdOperatorTable.CAST.createCall(operand1.getParserPosition(),
            operand1,
            SqlTypeUtil.convertTypeToSpec(typeFactory.createSqlType(
                    flt ? SqlTypeName.FLOAT : SqlTypeName.DOUBLE)));
    final SqlNode mulArg = SqlStdOperatorTable.MULTIPLY.createCall(pos, arg0, arg1);
    final SqlNode avgArg1 = SqlStdOperatorTable.AVG.createCall(pos, arg1);
    if (pop) {
      final SqlNode avgMulArg = SqlStdOperatorTable.AVG.createCall(pos, mulArg);
      final SqlNode avgArg0 = SqlStdOperatorTable.AVG.createCall(pos, arg0);
      final SqlNode mulAvgAvg =
              SqlStdOperatorTable.MULTIPLY.createCall(pos, avgArg0, avgArg1);
      final SqlNode covarPop =
              SqlStdOperatorTable.MINUS.createCall(pos, avgMulArg, mulAvgAvg);
      return SqlStdOperatorTable.CAST.createCall(pos,
              covarPop,
              SqlTypeUtil.convertTypeToSpec(typeFactory.createSqlType(
                      flt ? SqlTypeName.FLOAT : SqlTypeName.DOUBLE)));
    }
    final SqlNode sumMulArg = SqlStdOperatorTable.SUM.createCall(pos, mulArg);
    final SqlNode sumArg0 = SqlStdOperatorTable.SUM.createCall(pos, arg0);
    final SqlNode mulSumAvg =
            SqlStdOperatorTable.MULTIPLY.createCall(pos, sumArg0, avgArg1);
    final SqlNode sub = SqlStdOperatorTable.MINUS.createCall(pos, sumMulArg, mulSumAvg);
    final SqlNode count = SqlStdOperatorTable.COUNT.createCall(pos, operand0);
    final SqlNumericLiteral one = SqlLiteral.createExactNumeric("1", pos);
    final SqlNode countEqOne = SqlStdOperatorTable.EQUALS.createCall(pos, count, one);
    final SqlNode countMinusOne = SqlStdOperatorTable.MINUS.createCall(pos, count, one);
    final SqlLiteral nul = SqlLiteral.createNull(pos);
    SqlNodeList whenList1 = new SqlNodeList(pos);
    SqlNodeList thenList1 = new SqlNodeList(pos);
    whenList1.add(countEqOne);
    thenList1.add(nul);
    final SqlNode int_denominator = SqlStdOperatorTable.CASE.createCall(
            null, pos, null, whenList1, thenList1, countMinusOne);
    final SqlNode denominator = SqlStdOperatorTable.CAST.createCall(pos,
            int_denominator,
            SqlTypeUtil.convertTypeToSpec(typeFactory.createSqlType(
                    flt ? SqlTypeName.FLOAT : SqlTypeName.DOUBLE)));
    final SqlNode covarSamp =
            SqlStdOperatorTable.DIVIDE.createCall(pos, sub, denominator);
    return SqlStdOperatorTable.CAST.createCall(pos,
            covarSamp,
            SqlTypeUtil.convertTypeToSpec(typeFactory.createSqlType(
                    flt ? SqlTypeName.FLOAT : SqlTypeName.DOUBLE)));
  }

  private SqlNode expandCorrelation(
          final SqlBasicCall proj_call, RelDataTypeFactory typeFactory) {
    // Expand correlation coefficient
    if (proj_call.operandCount() != 2) {
      return null;
    }
    boolean flt;
    if (proj_call.getOperator().isName("CORR", false)
            || proj_call.getOperator().getName().equalsIgnoreCase("CORRELATION")) {
      // expand correlation coefficient
      flt = false;
    } else if (proj_call.getOperator().getName().equalsIgnoreCase("CORR_FLOAT")
            || proj_call.getOperator().getName().equalsIgnoreCase("CORRELATION_FLOAT")) {
      // expand correlation coefficient
      flt = true;
    } else {
      return null;
    }
    // corr(x, y) ==> (avg(x * y) - avg(x) * avg(y)) / (stddev_pop(x) *
    // stddev_pop(y))
    // ==> covar_pop(x, y) / (stddev_pop(x) * stddev_pop(y))
    final SqlNode operand0 = proj_call.operand(0);
    final SqlNode operand1 = proj_call.operand(1);
    final SqlParserPos pos = proj_call.getParserPosition();
    SqlNode covariance =
            expandCovariance(pos, operand0, operand1, true, flt, typeFactory);
    SqlNode stddev0 = expandVariance(pos, operand0, true, true, flt, typeFactory);
    SqlNode stddev1 = expandVariance(pos, operand1, true, true, flt, typeFactory);
    final SqlNode mulStddev =
            SqlStdOperatorTable.MULTIPLY.createCall(pos, stddev0, stddev1);
    final SqlNumericLiteral zero = SqlLiteral.createExactNumeric("0.0", pos);
    final SqlNode mulStddevEqZero =
            SqlStdOperatorTable.EQUALS.createCall(pos, mulStddev, zero);
    final SqlLiteral nul = SqlLiteral.createNull(pos);
    SqlNodeList whenList1 = new SqlNodeList(pos);
    SqlNodeList thenList1 = new SqlNodeList(pos);
    whenList1.add(mulStddevEqZero);
    thenList1.add(nul);
    final SqlNode denominator = SqlStdOperatorTable.CASE.createCall(
            null, pos, null, whenList1, thenList1, mulStddev);
    final SqlNode expanded_proj_call =
            SqlStdOperatorTable.DIVIDE.createCall(pos, covariance, denominator);
    MAPDLOGGER.debug("Expanded select_list SqlCall: " + proj_call.toString());
    MAPDLOGGER.debug("to : " + expanded_proj_call.toString());
    return expanded_proj_call;
  }

  public SqlIdentifierCapturer captureIdentifiers(String sql, boolean legacy_syntax)
          throws SqlParseException {
    try {
      Planner planner = getPlanner();
      SqlNode node = parseSql(sql, legacy_syntax, planner);
      return captureIdentifiers(node);
    } catch (Exception | Error e) {
      MAPDLOGGER.error("Error parsing sql: " + sql, e);
      return new SqlIdentifierCapturer();
    }
  }

  public SqlIdentifierCapturer captureIdentifiers(SqlNode node) throws SqlParseException {
    try {
      SqlIdentifierCapturer capturer = new SqlIdentifierCapturer();
      capturer.scan(node);
      return capturer;
    } catch (Exception | Error e) {
      MAPDLOGGER.error("Error parsing sql: " + node, e);
      return new SqlIdentifierCapturer();
    }
  }

  public int getCallCount() {
    return callCount;
  }

  protected RelDataTypeSystem createTypeSystem() {
    final MapDTypeSystem typeSystem = new MapDTypeSystem();
    return typeSystem;
  }
}
