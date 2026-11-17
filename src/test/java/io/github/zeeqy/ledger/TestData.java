package io.github.zeeqy.ledger;

import io.github.zeeqy.ledger.auth.UserService;
import io.github.zeeqy.ledger.payments.OperationsService;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Creates users straight in the DB so service tests don't depend on the auth layer. */
@Component
public class TestData {

    private final JdbcClient jdbc;
    private final OperationsService ops;
    private final UserService users;

    public TestData(JdbcClient jdbc, OperationsService ops, UserService users) {
        this.jdbc = jdbc;
        this.ops = ops;
        this.users = users;
    }

    public String token(UUID userId, String role) {
        return users.issue(userId, role).accessToken();
    }

    public UUID user(String role) {
        return jdbc.sql("INSERT INTO users (email, password_hash, role) VALUES (?, 'x', ?) RETURNING id")
                .params(UUID.randomUUID() + "@test.local", role)
                .query(UUID.class)
                .single();
    }

    /** A user with a wallet, topped up with {@code sen}. Returns the user id. */
    public UUID funded(long sen) {
        UUID user = user("USER");
        ops.createWallet(user);
        if (sen > 0) {
            ops.topUp(user, sen);
        }
        return user;
    }

    public UUID merchant() {
        UUID user = user("MERCHANT");
        ops.registerMerchant(user, "Kedai Test", null);
        return user;
    }

    public long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }
}
