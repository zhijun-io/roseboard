package com.roseboard.infrastructure.mybatis;

import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.postgresql.util.PGobject;

import java.lang.reflect.Field;
import java.sql.PreparedStatement;
import java.sql.SQLException;

@MappedTypes(tools.jackson.databind.JsonNode.class)
@MappedJdbcTypes(value = JdbcType.OTHER, includeNullJdbcType = false)
public class Jackson3JsonbTypeHandler extends Jackson3TypeHandler {
    public Jackson3JsonbTypeHandler(Class<?> type) {
        super(type);
    }

    public Jackson3JsonbTypeHandler(Class<?> type, Field field) {
        super(type, field);
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Object parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject json = new PGobject();
        json.setType("jsonb");
        json.setValue(toJson(parameter));
        ps.setObject(i, json);
    }
}
