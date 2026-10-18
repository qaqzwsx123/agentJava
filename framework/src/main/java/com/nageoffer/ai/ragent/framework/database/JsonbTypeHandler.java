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

package com.nageoffer.ai.ragent.framework.database;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
// 解决 Java 中的普通 String 类型与 PostgreSQL 数据库中的特殊类型 jsonb（二进制 JSON）之间的互相转换问题
public class JsonbTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
        // 1. 将 JSON 字符串包装为 PostgreSQL 专用对象，明确声明 jsonb 类型后绑定到 SQL 参数位置。
        PGobject jsonObject = new PGobject();
        jsonObject.setType("jsonb");
        jsonObject.setValue(parameter);
        // 2. 交给 JDBC 以 jsonb 类型发送，避免把 JSON 当成普通 varchar 绑定；这里不校验 JSON 内容。
        ps.setObject(i, jsonObject);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        // 按列名读取 jsonb 的字符串表示；数据库 NULL 由 JDBC 直接返回 null，不在这里解析 JSON。
        return rs.getString(columnName);
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        // 按列序号读取 JSON 文本，供按索引映射的查询结果使用，SQL 异常交给上层处理。
        return rs.getString(columnIndex);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        // 从存储过程的指定参数位置读取 JSON 文本，沿用与普通结果集相同的字符串表示。
        return cs.getString(columnIndex);
    }
}
