package org.rakam.ui.user;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.EventBus;
import com.google.common.io.Resources;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.lambdaworks.crypto.SCryptUtil;
import io.airlift.log.Logger;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.rakam.analysis.ApiKeyService.AccessKeyType;
import org.rakam.analysis.ApiKeyService.ProjectApiKeys;
import org.rakam.analysis.JDBCPoolDataSource;
import org.rakam.analysis.metadata.Metastore;
import org.rakam.config.EncryptionConfig;
import org.rakam.report.EmailClientConfig;
import org.rakam.ui.RakamUIConfig;
import org.rakam.ui.UIEvents;
import org.rakam.util.AlreadyExistsException;
import org.rakam.util.CryptUtil;
import org.rakam.util.JsonHelper;
import org.rakam.util.MailSender;
import org.rakam.util.RakamException;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.TransactionStatus;
import org.skife.jdbi.v2.exceptions.UnableToExecuteStatementException;
import org.skife.jdbi.v2.util.IntegerMapper;
import org.skife.jdbi.v2.util.StringMapper;

import javax.mail.MessagingException;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import static com.google.common.base.Charsets.UTF_8;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static java.lang.String.format;

public class WebUserService {
    private final static Logger LOGGER = Logger.get(WebUserService.class);

    private final DBI dbi;
    private final Metastore metastore;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[_A-Za-z0-9-\\+]+(\\.[_A-Za-z0-9-]+)*@"
            + "[A-Za-z0-9-]+(\\.[A-Za-z0-9]+)*(\\.[A-Za-z]{2,})$");
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z]).{8,}$");
    private final RakamUIConfig config;
    private final EncryptionConfig encryptionConfig;
    private final LoadingCache<ApiKey, Project> apiKeyReverseCache;
    private final EventBus eventBus;
    private MailSender mailSender;
    private final EmailClientConfig mailConfig;

    private static final Mustache resetPasswordHtmlCompiler;
    private static final Mustache resetPasswordTxtCompiler;
    private static final Mustache welcomeHtmlCompiler;
    private static final Mustache welcomeTxtCompiler;
    private static final Mustache resetPasswordTitleCompiler;
    private static final Mustache welcomeTitleCompiler;

    static {
        try {
            MustacheFactory mf = new DefaultMustacheFactory();

            resetPasswordHtmlCompiler = mf.compile(new StringReader(Resources.toString(
                    WebUserService.class.getResource("/mail/resetpassword/resetpassword.html"), UTF_8)), "resetpassword.html");
            resetPasswordTxtCompiler = mf.compile(new StringReader(Resources.toString(
                    WebUserService.class.getResource("/mail/resetpassword/resetpassword.txt"), UTF_8)), "resetpassword.txt");
            resetPasswordTitleCompiler = mf.compile(new StringReader(Resources.toString(
                    WebUserService.class.getResource("/mail/resetpassword/title.txt"), UTF_8)), "resetpassword_title.txt");

            welcomeHtmlCompiler = mf.compile(new StringReader(Resources.toString(
                    WebUserService.class.getResource("/mail/welcome/welcome.html"), UTF_8)), "welcome.html");
            welcomeTxtCompiler = mf.compile(new StringReader(Resources.toString(
                    WebUserService.class.getResource("/mail/welcome/welcome.txt"), UTF_8)), "welcome.txt");
            welcomeTitleCompiler = mf.compile(new StringReader(Resources.toString(
                    WebUserService.class.getResource("/mail/welcome/welcome.txt"), UTF_8)), "welcome_title.txt");
        } catch (IOException e) {
            throw Throwables.propagate(e);
        }
    }

    @Inject
    public WebUserService(@Named("ui.metadata.jdbc") JDBCPoolDataSource dataSource,
                          Metastore metastore,
                          EventBus eventBus,
                          RakamUIConfig config,
                          EncryptionConfig encryptionConfig,
                          EmailClientConfig mailConfig) {
        dbi = new DBI(dataSource);
        this.metastore = metastore;
        this.eventBus = eventBus;
        this.config = config;
        this.encryptionConfig = encryptionConfig;
        this.mailConfig = mailConfig;
        apiKeyReverseCache = CacheBuilder.newBuilder().build(new CacheLoader<ApiKey, Project>() {
            @Override
            public Project load(ApiKey apiKey) throws Exception {
                try (Connection conn = dbi.open().getConnection()) {
                    PreparedStatement ps = conn.prepareStatement(format("SELECT project, api_url FROM web_user_api_key WHERE %s = ?", apiKey.type.name()));
                    ps.setString(1, apiKey.key);
                    ResultSet resultSet = ps.executeQuery();
                    if (!resultSet.next()) {
                        throw new RakamException("API key is invalid", HttpResponseStatus.FORBIDDEN);
                    }
                    return new Project(resultSet.getString(1), resultSet.getString(2));
                } catch (SQLException e) {
                    throw Throwables.propagate(e);
                }
            }
        });
    }

    public WebUser createUser(String email, String password, String name) {
        if (!PASSWORD_PATTERN.matcher(password).matches()) {
            throw new RakamException("Password is not valid. Your password must contain at least one lowercase character, uppercase character and digit and be at least 8 characters. ", BAD_REQUEST);
        }

        if (config.getHashPassword()) {
            password = CryptUtil.encryptWithHMacSHA1(password, encryptionConfig.getSecretKey());
        }
        final String scrypt = SCryptUtil.scrypt(password, 2 << 14, 8, 1);

        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new RakamException("Email is not valid", BAD_REQUEST);
        }

        Map<String, Object> scopes = ImmutableMap.of("product_name", "Rakam");

        WebUser webuser = null;

        try (Handle handle = dbi.open()) {
            try {
                int id = handle.createStatement("INSERT INTO web_user (email, password, name, created_at) VALUES (:email, :password, :name, now())")
                        .bind("email", email)
                        .bind("name", name)
                        .bind("password", scrypt).executeAndReturnGeneratedKeys(IntegerMapper.FIRST).first();
                webuser = new WebUser(id, email, name, ImmutableList.of());
            } catch (UnableToExecuteStatementException e) {
                Map<String, Object> existingUser = handle.createQuery("SELECT created_at FROM web_user WHERE email = :email").bind("email", email).first();
                if (existingUser != null) {
                    if (existingUser.get("created_at") != null) {
                        throw new AlreadyExistsException("A user with same email address", EXPECTATION_FAILED);
                    } else {
                        // somebody gave access for a project to this email address
                        int id = handle.createStatement("UPDATE web_user SET password = :password, name = :name, created_at = now() WHERE email = :email")
                                .bind("email", email)
                                .bind("name", name)
                                .bind("password", scrypt).executeAndReturnGeneratedKeys(IntegerMapper.FIRST).first();
                        if (id > 0) {
                            webuser = new WebUser(id, email, name, ImmutableList.of());
                        }
                    }
                }

                if (webuser == null) {
                    throw e;
                }
            }
        }

        sendMail(welcomeTitleCompiler, welcomeTxtCompiler,
                welcomeHtmlCompiler, email, scopes);

        return webuser;
    }

    public Project getProjectOfApiKey(String apiKey, AccessKeyType type) {
        try {
            return apiKeyReverseCache.getUnchecked(new ApiKey(apiKey, type));
        } catch (UncheckedExecutionException e) {
            throw Throwables.propagate(e.getCause());
        }
    }

    public void updateUserInfo(int id, String name) {
        try (Handle handle = dbi.open()) {
            handle.createStatement("UPDATE web_user SET name = :name WHERE id = :id")
                    .bind("id", id)
                    .bind("name", name).executeAndReturnGeneratedKeys(IntegerMapper.FIRST).first();
        }
    }

    public void updateUserPassword(int id, String oldPassword, String newPassword) {
        final String scrypt = SCryptUtil.scrypt(newPassword, 2 << 14, 8, 1);

        if (!PASSWORD_PATTERN.matcher(newPassword).matches()) {
            throw new RakamException("Password is not valid. Your password must contain at least one lowercase character, uppercase character and digit and be at least 8 characters. ", BAD_REQUEST);
        }

        if (config.getHashPassword()) {
            oldPassword = CryptUtil.encryptWithHMacSHA1(oldPassword, encryptionConfig.getSecretKey());
        }

        try (Handle handle = dbi.open()) {
            String hashedPass = handle.createQuery("SELECT password FROM web_user WHERE id = :id")
                    .bind("id", id).map(StringMapper.FIRST).first();
            if (hashedPass == null) {
                throw new RakamException("User does not exist", BAD_REQUEST);
            }
            if (!SCryptUtil.check(oldPassword, hashedPass)) {
                throw new RakamException("Password is wrong", BAD_REQUEST);
            }
            handle.createStatement("UPDATE web_user SET password = :password WHERE id = :id")
                    .bind("id", id)
                    .bind("password", scrypt).execute();
        }
    }

    private ProjectApiKeys createApiKeys(String apiUrl, String project, String masterKey) {
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(apiUrl + "/project/create-api-keys")
                    .openConnection();
            con.setRequestMethod("POST");
            con.setRequestProperty("api_key", masterKey);

            con.setDoOutput(true);
            DataOutputStream wr = new DataOutputStream(con.getOutputStream());
            HashMap<Object, Object> obj = new HashMap<>();
            obj.put("project", project);
            wr.write(JsonHelper.encodeAsBytes(obj));
            wr.flush();
            wr.close();

            BufferedReader in = new BufferedReader(new InputStreamReader(
                    con.getResponseCode() == 200 ? con.getInputStream() : con.getErrorStream()));
            String inputLine;
            StringBuffer response = new StringBuffer();

            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();

            if (con.getResponseCode() != 200) {
                if (con.getResponseCode() == 0) {
                    throw new RakamException("The API is unreachable.", BAD_REQUEST);
                }
                if (con.getResponseCode() >= 400 && con.getResponseCode() < 500) {
                    Map<String, Object> message = JsonHelper.read(response.toString(), Map.class);
                    throw new RakamException(message.get("error").toString(), BAD_REQUEST);
                }

                throw new RakamException("The API returned invalid status code " + con.getResponseCode(), BAD_REQUEST);
            }

            try {
                return JsonHelper.read(response.toString(), ProjectApiKeys.class);
            } catch (Exception e) {
                throw new RakamException("The API returned invalid response. Not a Rakam API?", BAD_REQUEST);
            }
        } catch (IOException e) {
            throw new RakamException("The API is unreachable.", BAD_REQUEST);
        }
    }

    public WebUser.UserApiKey createProject(int user, String apiUrl, String project) {
        String lockKey;
        ProjectApiKeys apiKeys;

        try (Handle handle = dbi.open()) {
            lockKey = handle.createQuery("SELECT lock_key FROM rakam_cluster WHERE user_id = :userId AND api_url = :apiUrl")
                    .bind("userId", user).bind("apiUrl", apiUrl)
                    .map(StringMapper.FIRST).first();
        }

        try {
            HttpURLConnection con = (HttpURLConnection) new URL(apiUrl + "/project/create")
                    .openConnection();
            con.setRequestMethod("POST");

            con.setDoOutput(true);
            DataOutputStream wr = new DataOutputStream(con.getOutputStream());
            HashMap<Object, Object> obj = new HashMap<>();
            obj.put("lock_key", lockKey);
            obj.put("name", project);
            wr.write(JsonHelper.encodeAsBytes(obj));
            wr.flush();
            wr.close();

            BufferedReader in = new BufferedReader(new InputStreamReader(
                    con.getResponseCode() == 200 ? con.getInputStream() : con.getErrorStream()));
            String inputLine;
            StringBuffer response = new StringBuffer();

            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();

            if (con.getResponseCode() != 200) {
                if (con.getResponseCode() == 403) {
                    throw new RakamException("The lock key is not valid.", BAD_REQUEST);
                }
                if (con.getResponseCode() == 0) {
                    throw new RakamException("The API is unreachable.", BAD_REQUEST);
                }
                if (con.getResponseCode() == 400) {
                    Map<String, Object> message = JsonHelper.read(response.toString(), Map.class);
                    throw new RakamException(message.get("error").toString(), BAD_REQUEST);
                }

                throw new RakamException("The API returned invalid status code " + con.getResponseCode(), BAD_REQUEST);
            }

            try {
                apiKeys = JsonHelper.read(response.toString(), ProjectApiKeys.class);
            } catch (Exception e) {
                throw new RakamException("The API returned invalid response. Not a Rakam API?", BAD_REQUEST);
            }
        } catch (IOException e) {
            throw new RakamException("The API is unreachable.", BAD_REQUEST);
        }

        int projectId;
        try (Handle handle = dbi.open()) {
            try {
                projectId = (Integer) handle.createStatement("INSERT INTO web_user_project " +
                        "(project, api_url, created_user) " +
                        "VALUES (:project, :apiUrl, :userId)")
                        .bind("userId", user)
                        .bind("project", project)
                        .bind("apiUrl", apiUrl)
                        .executeAndReturnGeneratedKeys().first().get("id");
            } catch (Exception e) {
                projectId = handle.createQuery("SELECT id FROM web_user_project WHERE project = :project AND api_url = :apiUrl")
                        .bind("project",project)
                        .bind("apiUrl", apiUrl).map(IntegerMapper.FIRST).first();
            }

            handle.createStatement("INSERT INTO web_user_api_key " +
                    "(user_id, project_id, read_key, write_key, master_key) " +
                    "VALUES (:userId, :project, :readKey, :writeKey, :masterKey)")
                    .bind("userId", user)
                    .bind("project", projectId)
                    .bind("readKey", apiKeys.readKey())
                    .bind("writeKey", apiKeys.writeKey())
                    .bind("masterKey", apiKeys.masterKey())
                    .execute();
        }

        eventBus.post(new UIEvents.ProjectCreatedEvent(projectId));
        return new WebUser.UserApiKey(projectId, apiKeys.readKey(), apiKeys.writeKey(),
                apiKeys.masterKey());
    }

    public void revokeUserAccess(String project, String apiUrl, String email) {
        try (Handle handle = dbi.open()) {
            handle.createStatement("DELETE FROM web_user_project WHERE project = :project AND api_url = :apiUrl AND user_id = (SELECT id FROM web_user WHERE email = :email)")
                    .bind("project", project)
                    .bind("email", email)
                    .bind("apiUrl", apiUrl)
                    .bind("project", project)
                    .execute();
        }
    }

    public void performRecoverPassword(String key, String hash, String newPassword) {
        if (!PASSWORD_PATTERN.matcher(newPassword).matches()) {
            throw new RakamException("Password is not valid. " +
                    "Your password must contain at least one lowercase character, uppercase character and digit and be at least 8 characters. ", BAD_REQUEST);
        }

        String realKey = new String(Base64.getDecoder().decode(key.getBytes(UTF_8)), UTF_8);
        if (!CryptUtil.encryptWithHMacSHA1(realKey, encryptionConfig.getSecretKey()).equals(hash)) {
            throw new RakamException("Invalid token", UNAUTHORIZED);
        }

        String[] split = realKey.split("|", 2);
        if (split.length != 2) {
            throw new RakamException(BAD_REQUEST);
        }
        try {
            if (Instant.ofEpochSecond(Long.parseLong(split[0])).compareTo(Instant.now()) > 0) {
                throw new RakamException("Token expired", UNAUTHORIZED);
            }
        } catch (NumberFormatException e) {
            throw new RakamException("Invalid token", UNAUTHORIZED);
        }

        final String scrypt = SCryptUtil.scrypt(newPassword, 2 << 14, 8, 1);

        try (Handle handle = dbi.open()) {
            handle.createStatement("UPDATE web_user SET password = :password WHERE email = :email AND password = :password")
                    .bind("email", split[1])
                    .bind("password", scrypt).execute();
        }
    }

    public void prepareRecoverPassword(String email) {
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new RakamException("Email is not valid", BAD_REQUEST);
        }
        long expiration = Instant.now().plus(3, ChronoUnit.HOURS).getEpochSecond();
        String key = expiration + "|" + email;
        String hash = CryptUtil.encryptWithHMacSHA1(key, encryptionConfig.getSecretKey());
        String encoded = new String(Base64.getEncoder().encode(key.getBytes(UTF_8)), UTF_8);

        Map<String, Object> scopes;
        try {
            scopes = ImmutableMap.of("product_name", "Rakam",
                    "action_url", String.format("%s/perform-recover-password?key=%s&hash=%s",
                            mailConfig.getSiteUrl(), URLEncoder.encode(encoded, "UTF-8"), URLEncoder.encode(hash, "UTF-8")));
        } catch (UnsupportedEncodingException e) {
            throw Throwables.propagate(e);
        }

        sendMail(resetPasswordTitleCompiler, resetPasswordTxtCompiler, resetPasswordHtmlCompiler, email, scopes).join();
    }

    private CompletableFuture sendMail(Mustache titleCompiler, Mustache contentCompiler, Mustache htmlCompiler, String email, Map<String, Object> data) {
        StringWriter writer;

        writer = new StringWriter();
        contentCompiler.execute(writer, data);
        String txtContent = writer.toString();

        writer = new StringWriter();
        htmlCompiler.execute(writer, data);
        String htmlContent = writer.toString();

        writer = new StringWriter();
        titleCompiler.execute(writer, data);
        String title = writer.toString();

        if (mailSender == null) {
            synchronized (this) {
                mailSender = mailConfig.getMailSender();
            }
        }

        return CompletableFuture.runAsync(() -> {
            try {
                mailSender.sendMail(email, title, txtContent, Optional.of(htmlContent));
            } catch (MessagingException e) {
                LOGGER.error(e, "Unable to send mail");
            }
        });
    }

    public void deleteProject(int user, String apiUrl, String name) {
        try (Handle handle = dbi.open()) {
            handle.createStatement("DELETE FROM web_user_api_key WHERE user_id = :userId AND project_id = (SELECT id FROM web_user_project WHERE project = :project AND api_url = :apiUrl)")
                    .bind("userId", user)
                    .bind("project", name)
                    .bind("apiUrl", apiUrl)
                    .execute();

            handle.createStatement("DELETE FROM web_user_project WHERE project = :project AND api_url = :apiUrl " +
                    "AND (SELECT count(*) FROM web_user_api_key WHERE project_id = (SELECT id FROM web_user_project WHERE project = :project AND api_url = :apiUrl)) = 0")
                    .bind("userId", user)
                    .bind("project", name)
                    .bind("apiUrl", apiUrl)
                    .execute();
        }
    }

    public WebUser.UserApiKey registerProject(int user, String apiUrl, String project, String readKey, String writeKey, String masterKey) {
        int projectId;
        try (Handle handle = dbi.open()) {
            try {
                projectId = (Integer) handle.createStatement("INSERT INTO web_user_project " +
                        "(project, api_url, created_user) " +
                        "VALUES (:project, :apiUrl, :userId)")
                        .bind("userId", user)
                        .bind("project", project)
                        .bind("apiUrl", apiUrl)
                        .executeAndReturnGeneratedKeys().first().get("id");
            } catch (Exception e) {
                projectId = handle.createQuery("SELECT id FROM web_user_project WHERE project = :project AND api_url = :apiUrl")
                        .bind("project",project)
                        .bind("apiUrl", apiUrl).map(IntegerMapper.FIRST).first();
            }

            handle.createStatement("INSERT INTO web_user_api_key " +
                    "(user_id, project_id, read_key, write_key, master_key) " +
                    "VALUES (:userId, :project, :readKey, :writeKey, :masterKey)")
                    .bind("userId", user)
                    .bind("project", projectId)
                    .bind("readKey", readKey)
                    .bind("writeKey", writeKey)
                    .bind("masterKey", masterKey)
                    .execute();
        }

        eventBus.post(new UIEvents.ProjectCreatedEvent(projectId));
        return new WebUser.UserApiKey(projectId, readKey, writeKey, masterKey);
    }

    public static class UserAccess {
        public final int project;
        public final String email;
        public final String scope_expression;
        public final boolean readKey;
        public final boolean writeKey;
        public final boolean masterKey;

        public UserAccess(int project, String email, String scope_expression, boolean readKey, boolean writeKey, boolean masterKey) {
            this.project = project;
            this.email = email;
            this.scope_expression = scope_expression;
            this.readKey = readKey;
            this.writeKey = writeKey;
            this.masterKey = masterKey;
        }
    }

    public List<UserAccess> getUserAccessForAllProjects(int user, int project) {
        try (Handle handle = dbi.open()) {
            return handle.createQuery("SELECT web_user.email, scope_expression, " +
                    "read_key is not null as read_key, " +
                    "write_key is not null as write_key, " +
                    "master_key is not null as master_key FROM web_user_api_key keys " +
                    "JOIN web_user ON (web_user.id = keys.user_id) " +
                    "WHERE keys.project_id = :project " +
                    "ORDER BY keys.created_at")
                    .bind("user", user)
                    .bind("project", project).map((i, resultSet, statementContext) -> {
                        return new UserAccess(project, resultSet.getString(1), resultSet.getString(2),
                                resultSet.getBoolean(3), resultSet.getBoolean(4), resultSet.getBoolean(5));
                    }).list();
        }
    }

    public void giveAccessToUser(String project, String apiUrl, String masterKey, String email, String scopePermission, boolean hasReadPermission, boolean hasWritePermission, boolean isAdmin) {
        Map.Entry<Integer, Boolean> userId;
        try (Handle handle = dbi.open()) {
            userId = handle.createQuery("SELECT id, coalesce(bool_or(p.master_key is not null), false) FROM web_user u LEFT JOIN web_user_project p ON (p.user_id = u.id AND p.project = :project AND p.api_url = :apiUrl) WHERE email = :email GROUP BY 1").bind("email", email)
                    .bind("project", project)
                    .bind("apiUrl", apiUrl)
                    .map((i, resultSet, statementContext) -> {
                        return new SimpleImmutableEntry<>(resultSet.getInt(1), resultSet.getBoolean(2));
                    }).first();

            if (userId == null) {
                Integer id = handle.createStatement("INSERT INTO web_user (email) VALUES (:email)")
                        .bind("email", email).executeAndReturnGeneratedKeys(IntegerMapper.FIRST).first();
                userId = new SimpleImmutableEntry<>(id, false);
            }

            if (userId.getValue()) {
                return;
            }
        }

        final ProjectApiKeys apiKeys = createApiKeys(apiUrl, project, masterKey);

        final Map.Entry<Integer, Boolean> finalUserId = userId;
        dbi.inTransaction((Handle handle, TransactionStatus transactionStatus) -> {
            handle.createStatement("DELETE FROM web_user_project WHERE user_id = :user AND api_url = :apiUrl AND project = :project")
                    .bind("user", finalUserId.getKey())
                    .bind("apiUrl", apiUrl)
                    .bind("project", project).execute();

            handle.createStatement("INSERT INTO web_user_project (user_id, project, api_url, read_key, write_key, scope_expression, master_key) " +
                    " VALUES (:user, :project, :apiUrl, :readKey, :writeKey, :scope, :masterKey)")
                    .bind("user", finalUserId.getKey())
                    .bind("apiUrl", apiUrl)
                    .bind("project", project)
                    .bind("scope", scopePermission)
                    .bind("masterKey", isAdmin ? apiKeys.masterKey() : null)
                    .bind("writeKey", hasWritePermission ? apiKeys.writeKey() : null)
                    .bind("readKey", hasReadPermission ? apiKeys.readKey() : null).execute();

            return null;
        });
    }

    public ProjectApiKeys createApiKeys(int user, String project, String apiUrl) {
        try (Handle handle = dbi.open()) {
            Optional<String> any = getUserApiKeys(handle, user).stream()
                    .filter(a -> a.name.equals(project) && a.apiKeys.stream().anyMatch(e -> e.masterKey() != null))
                    .map(z -> z.apiKeys.stream().filter(g -> g.masterKey() != null).findFirst().map(c -> c.masterKey()).get())
                    .findAny();

            // TODO: check scope permission keys
            any.orElseThrow(() -> new RakamException(UNAUTHORIZED));

            final ProjectApiKeys apiKeys = createApiKeys(apiUrl, project, any.get());

            handle.createStatement("INSERT INTO web_user_project " +
                    "(user_id, project, api_url, read_key, write_key, master_key) " +
                    "VALUES (:userId, :project, :apiUrl, :readKey, :writeKey, :masterKey)")
                    .bind("userId", user)
                    .bind("project", project)
                    .bind("apiUrl", apiUrl)
                    .bind("readKey", apiKeys.readKey())
                    .bind("writeKey", apiKeys.writeKey())
                    .bind("masterKey", apiKeys.masterKey())
                    .execute();

            return apiKeys;
        }
    }


    public Optional<WebUser> login(String email, String password) {
        String hashedPassword;
        String name;
        int id;

        if (config.getHashPassword()) {
            password = CryptUtil.encryptWithHMacSHA1(password, encryptionConfig.getSecretKey());
        }

        List<WebUser.Project> projects;

        try (Handle handle = dbi.open()) {
            final Map<String, Object> data = handle
                    .createQuery("SELECT id, name, password FROM web_user WHERE email = :email")
                    .bind("email", email).first();
            if (data == null) {
                return Optional.empty();
            }
            hashedPassword = (String) data.get("password");
            name = (String) data.get("name");
            id = (int) data.get("id");

            // TODO move this heavy operation outside of the connection scope.
            if (!SCryptUtil.check(password, hashedPassword)) {
                return Optional.empty();
            }

            projects = getUserApiKeys(handle, id);
        }

        return Optional.of(new WebUser(id, email, name, projects));
    }


    public Optional<WebUser> getUser(int id) {
        String name;
        String email;

        List<WebUser.Project> projectDefinitions;

        try (Handle handle = dbi.open()) {
            final Map<String, Object> data = handle
                    .createQuery("SELECT id, name, email FROM web_user WHERE id = :id")
                    .bind("id", id).first();
            if (data == null) {
                return Optional.empty();
            }
            name = (String) data.get("name");
            email = (String) data.get("email");
            id = (int) data.get("id");

            projectDefinitions = getUserApiKeys(handle, id);
        }

        return Optional.of(new WebUser(id, email, name, projectDefinitions));
    }

    public static class ProjectDefinition {
        public final String project;
        public final String scope_expression;
        public final String api_url;
        public final String read_key;
        public final String write_key;
        public final String master_key;

        public ProjectDefinition(String project, String apiUrl, String scope_expression, String readKey, String writeKey, String masterKey) {
            this.project = project;
            this.scope_expression = scope_expression;
            this.read_key = readKey;
            this.write_key = writeKey;
            this.master_key = masterKey;
            this.api_url = apiUrl;
        }
    }

    private List<WebUser.Project> getUserApiKeys(Handle handle, int userId) {
        List<WebUser.Project> list = new ArrayList<>();
        handle.createQuery("SELECT project.id, project.project, project.api_url, api_key.master_key, api_key.read_key, api_key.write_key " +
                " FROM web_user_project project " +
                " JOIN web_user_api_key api_key ON (api_key.project_id = project.id)" +
                " WHERE api_key.user_id = :user ORDER BY project.id")
                .bind("user", userId)
                .map((index, r, ctx) -> {
                    int id = r.getInt(1);
                    String name = r.getString(2);
                    String url = r.getString(3);
                    list.stream().filter(e -> e.id == id).findFirst()
                            .orElseGet(() -> {
                                WebUser.Project project = new WebUser.Project(id, name, url, new ArrayList<>());
                                list.add(project);
                                return project;
                            }).apiKeys
                            .add(ProjectApiKeys.create(r.getString(4), r.getString(5), r.getString(6)));
                    return null;
                }).list();

        return list;
    }

    public void revokeApiKeys(int user, String apiUrl, String masterKey) {
        try (Handle handle = dbi.open()) {
            handle.createStatement("DELETE FROM web_user_project " +
                    "WHERE user_id = :user_id AND api_url = :apiUrl AND master_key = :masterKey")
                    .bind("user_id", user)
                    .bind("apiUrl", apiUrl)
                    .bind("masterKey", masterKey).execute();
        }
    }

    public static final class Project {
        public final String project;
        public final String apiUrl;

        public Project(String project, String apiUrl) {
            this.project = project;
            this.apiUrl = apiUrl;
        }
    }

    public static final class ApiKey {
        public final String key;
        public final AccessKeyType type;

        public ApiKey(String key, AccessKeyType type) {
            this.key = key;
            this.type = type;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ApiKey)) return false;

            ApiKey apiKey = (ApiKey) o;

            if (!key.equals(apiKey.key)) return false;
            return type == apiKey.type;

        }

        @Override
        public int hashCode() {
            int result = key.hashCode();
            result = 31 * result + type.hashCode();
            return result;
        }
    }
}
