/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.sharding.route.engine;

import org.apache.shardingsphere.infra.binder.type.CursorAvailable;
import org.apache.shardingsphere.infra.config.props.ConfigurationProperties;
import org.apache.shardingsphere.infra.metadata.database.ShardingSphereDatabase;
import org.apache.shardingsphere.infra.metadata.database.rule.ShardingSphereRuleMetaData;
import org.apache.shardingsphere.infra.route.SQLRouter;
import org.apache.shardingsphere.infra.route.context.RouteContext;
import org.apache.shardingsphere.infra.session.connection.ConnectionContext;
import org.apache.shardingsphere.infra.session.query.QueryContext;
import org.apache.shardingsphere.sharding.cache.route.CachedShardingSQLRouter;
import org.apache.shardingsphere.sharding.constant.ShardingOrder;
import org.apache.shardingsphere.sharding.route.engine.condition.ShardingCondition;
import org.apache.shardingsphere.sharding.route.engine.condition.ShardingConditions;
import org.apache.shardingsphere.sharding.route.engine.condition.engine.ShardingConditionEngine;
import org.apache.shardingsphere.sharding.route.engine.type.ShardingRouteEngineFactory;
import org.apache.shardingsphere.sharding.route.engine.validator.ShardingStatementValidator;
import org.apache.shardingsphere.sharding.route.engine.validator.ShardingStatementValidatorFactory;
import org.apache.shardingsphere.sharding.rule.ShardingRule;
import org.apache.shardingsphere.sql.parser.sql.common.statement.SQLStatement;
import org.apache.shardingsphere.sql.parser.sql.common.statement.dml.DMLStatement;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Sharding SQL router.
 */
public final class ShardingSQLRouter implements SQLRouter<ShardingRule> {
    
    @Override
    public RouteContext createRouteContext(final QueryContext queryContext, final ShardingSphereRuleMetaData globalRuleMetaData, final ShardingSphereDatabase database, final ShardingRule rule,
                                           final ConfigurationProperties props, final ConnectionContext connectionContext) {
        if (rule.isShardingCacheEnabled()) {
            Optional<RouteContext> result = new CachedShardingSQLRouter()
                    .loadRouteContext(this::createRouteContext0, queryContext, globalRuleMetaData, database, rule.getShardingCache(), props, connectionContext);
            if (result.isPresent()) {
                return result.get();
            }
        }
        return createRouteContext0(queryContext, globalRuleMetaData, database, rule, props, connectionContext);
    }
    
    @SuppressWarnings({"rawtypes", "unchecked"})
    private RouteContext createRouteContext0(final QueryContext queryContext, final ShardingSphereRuleMetaData globalRuleMetaData, final ShardingSphereDatabase database, final ShardingRule rule,
                                             final ConfigurationProperties props, final ConnectionContext connectionContext) {
        SQLStatement sqlStatement = queryContext.getSqlStatementContext().getSqlStatement();
        ShardingConditions shardingConditions = createShardingConditions(queryContext, globalRuleMetaData, database, rule);
        Optional<ShardingStatementValidator> validator = ShardingStatementValidatorFactory.newInstance(sqlStatement, shardingConditions);
        validator.ifPresent(optional -> optional.preValidate(rule, queryContext.getSqlStatementContext(), queryContext.getParameters(), database, props));
        if (sqlStatement instanceof DMLStatement && shardingConditions.isNeedMerge()) {
            shardingConditions.merge();
        }
        RouteContext result = ShardingRouteEngineFactory.newInstance(rule, database, queryContext, shardingConditions, props, connectionContext).route(rule);
        validator.ifPresent(optional -> optional.postValidate(rule, queryContext.getSqlStatementContext(), queryContext.getHintValueContext(), queryContext.getParameters(), database, props, result));
        return result;
    }
    
    private ShardingConditions createShardingConditions(final QueryContext queryContext,
                                                        final ShardingSphereRuleMetaData globalRuleMetaData, final ShardingSphereDatabase database, final ShardingRule rule) {
        List<ShardingCondition> shardingConditions;
        if (queryContext.getSqlStatementContext().getSqlStatement() instanceof DMLStatement || queryContext.getSqlStatementContext() instanceof CursorAvailable) {
            shardingConditions = new ShardingConditionEngine(globalRuleMetaData, database, rule).createShardingConditions(queryContext.getSqlStatementContext(), queryContext.getParameters());
        } else {
            shardingConditions = Collections.emptyList();
        }
        return new ShardingConditions(shardingConditions, queryContext.getSqlStatementContext(), rule);
    }
    
    @Override
    public void decorateRouteContext(final RouteContext routeContext, final QueryContext queryContext, final ShardingSphereDatabase database, final ShardingRule rule,
                                     final ConfigurationProperties props, final ConnectionContext connectionContext) {
        // TODO
    }
    
    @Override
    public int getOrder() {
        return ShardingOrder.ORDER;
    }
    
    @Override
    public Class<ShardingRule> getTypeClass() {
        return ShardingRule.class;
    }
}
