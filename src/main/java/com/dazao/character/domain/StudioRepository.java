package com.dazao.character.domain;

import com.dazao.character.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 所有者条件隔离角色和会话；H2 文件保存本地业务数据。 */
@Repository
public class StudioRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public StudioRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        jdbc.update("UPDATE chat_turns SET status='FAILED' WHERE status='PENDING'");
    }

    public long owner(Principal principal) {
        return jdbc.queryForObject("SELECT id FROM studio_users WHERE username=?", Long.class, principal.getName());
    }

    public String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "数据保存失败");
        }
    }

    public CharacterDefinition decode(String value) {
        try {
            return json.readValue(value, CharacterDefinition.class);
        } catch (JsonProcessingException error) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "角色数据无法读取");
        }
    }

    public List<CharacterView> characters(long owner) {
        return jdbc.query("SELECT * FROM characters WHERE owner_id=? ORDER BY updated_at DESC,id",
                (rs, row) -> new CharacterView(rs.getString("id"), rs.getInt("version"),
                        decode(rs.getString("definition")), rs.getTimestamp("updated_at").toInstant().toString()), owner);
    }

    public CharacterView character(long owner, String id) {
        return characters(owner).stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "角色不存在"));
    }

    public CharacterView create(long owner, CharacterDefinition definition) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO characters(id,owner_id,version,definition) VALUES(?,?,1,?)", id, owner, encode(definition));
        return character(owner, id);
    }

    public CharacterView update(long owner, String id, int version, CharacterDefinition definition) {
        character(owner, id);
        int changed = jdbc.update("UPDATE characters SET definition=?,version=version+1,updated_at=CURRENT_TIMESTAMP"
                + " WHERE id=? AND owner_id=? AND version=?", encode(definition), id, owner, version);
        if (changed == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "角色已在其他页面修改，请重新打开后编辑");
        }
        return character(owner, id);
    }

    @Transactional
    public void deleteCharacter(long owner, String id) {
        character(owner, id);
        Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM chat_turns t JOIN conversations c"
                + " ON t.conversation_id=c.id WHERE c.owner_id=? AND c.character_id=? AND t.status='PENDING'",
                Integer.class, owner, id);
        if (pending != null && pending > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "角色正在回复，请稍后再删除");
        }
        jdbc.update("DELETE FROM chat_turns WHERE conversation_id IN"
                + " (SELECT id FROM conversations WHERE owner_id=? AND character_id=?)", owner, id);
        jdbc.update("DELETE FROM conversations WHERE owner_id=? AND character_id=?", owner, id);
        jdbc.update("DELETE FROM characters WHERE owner_id=? AND id=?", owner, id);
    }

    public List<ConversationView> conversations(long owner) {
        return jdbc.query("SELECT * FROM conversations WHERE owner_id=? ORDER BY created_at DESC,id",
                (rs, row) -> new ConversationView(rs.getString("id"), rs.getString("character_id"),
                        rs.getInt("character_version"), decode(rs.getString("definition")),
                        rs.getTimestamp("created_at").toInstant().toString()), owner);
    }

    public ConversationView conversation(long owner, String id) {
        return conversations(owner).stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "会话不存在"));
    }

    public ConversationView createConversation(long owner, String characterId) {
        CharacterView character = character(owner, characterId);
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO conversations(id,owner_id,character_id,character_version,definition) VALUES(?,?,?,?,?)",
                id, owner, characterId, character.version(), encode(character.definition()));
        return conversation(owner, id);
    }

    public List<TurnView> turns(long owner, String conversationId) {
        conversation(owner, conversationId);
        return jdbc.query("SELECT * FROM chat_turns WHERE conversation_id=? ORDER BY created_at,id",
                (rs, row) -> new TurnView(rs.getString("id"), rs.getString("user_content"),
                        rs.getString("assistant_content"), rs.getString("status")), conversationId);
    }

    @Transactional
    public void deleteConversation(long owner, String id) {
        conversation(owner, id);
        Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM chat_turns WHERE conversation_id=?"
                + " AND status='PENDING'", Integer.class, id);
        if (pending != null && pending > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "回复尚未结束，请稍后删除");
        }
        jdbc.update("DELETE FROM chat_turns WHERE conversation_id=?", id);
        jdbc.update("DELETE FROM conversations WHERE owner_id=? AND id=?", owner, id);
    }

    public String startTurn(String conversationId, String content) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO chat_turns(id,conversation_id,user_content,status) VALUES(?,?,?,'PENDING')",
                id, conversationId, content);
        return id;
    }

    public void finishTurn(String id, String reply, boolean success) {
        jdbc.update("UPDATE chat_turns SET assistant_content=?,status=? WHERE id=? AND status='PENDING'",
                reply, success ? "DONE" : "FAILED", id);
    }

    public record CharacterView(String id, int version, CharacterDefinition definition, String updatedAt) {
    }

    public record ConversationView(String id, String characterId, int characterVersion,
                                   CharacterDefinition definition, String createdAt) {
    }

    public record TurnView(String id, String userContent, String assistantContent, String status) {
    }
}
