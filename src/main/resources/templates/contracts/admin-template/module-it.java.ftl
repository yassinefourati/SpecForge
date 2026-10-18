package [=pkg];

import static com.admintemplate.support.TestUsers.admin;
import static com.admintemplate.support.TestUsers.member;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.admintemplate.support.IntegrationTest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Every table of the "[=module.name]" module: create, read, list, update and delete through the API, in the
 * order their required relations need. Runs on the project's own IntegrationTest (PostgreSQL, Flyway, mock JWTs).
 *
 * [=generatedNote]
 */
class [=module.cap]ModuleIT extends IntegrationTest {

    /** JSON of each created row's key, by class: the bodies of the rows that refer to it use it. */
    private final Map<String, String> keys = new HashMap<>();
    private final Deque<String> created = new ArrayDeque<>();
    /** distinct per run, so unique columns never clash with rows of an earlier run */
    private final String run = Long.toString(System.nanoTime() % 1_000_000_000L, 36);

    @Test
    void crudOnEveryTable() throws Exception {
<#list module.creationOrder as x>
<#if x.testable>
        [=x.var]();
<#else>
        // [=x.className]: not exercised (no create operation, or a required relation that cannot be created first)
</#if>
</#list>
        // delete in reverse order, so no row is still referenced
        while (!created.isEmpty()) {
            String path = created.pop();
            mvc.perform(delete(path).with(admin())).andExpect(status().isNoContent());
            mvc.perform(get(path).with(admin())).andExpect(status().isNotFound());
        }
    }
<#if module.permission?has_content>

    @Test
    void changesNeedThePermission() throws Exception {
        // a DELETE carries no body, so the permission is checked before anything else
<#list module.creationOrder as x><#if x.delete>
        mvc.perform(delete("/api/[=x.path]/0").with(member())).andExpect(status().isForbidden());
<#break></#if></#list>
<#list module.creationOrder as x><#if x.list>
        mvc.perform(get("/api/[=x.path]").with(member())).andExpect(status().isOk());
<#break></#if></#list>
    }
</#if>

    /** Text that is unique per run: the given part, then the run's random part, cut to the column's length. */
    private String text(String part, int max) {
        String s = part + run;
        return max > 0 && s.length() > max ? s.substring(0, max) : s;
    }
<#list module.creationOrder as x>
<#if x.testable>

    private void [=x.var]() throws Exception {
        String body = [=x.createBody];
        String response = mvc.perform(post("/api/[=x.path]").with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String key = json.readTree(response).get("[=x.idName]").toString();
        keys.put("[=x.className]", key);
        String path = "/api/[=x.path]/" + key.replace("\"", "");
<#if x.read>
        mvc.perform(get(path).with(admin())).andExpect(status().isOk()).andExpect(jsonPath("$.[=x.idName]").exists());
</#if>
<#if x.list>
        mvc.perform(get("/api/[=x.path]").param("sort", "[=x.defaultSort],desc").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").isNumber());
</#if>
<#if x.update>
        mvc.perform(put(path).with(admin()).contentType(MediaType.APPLICATION_JSON).content([=x.updateBody]))
                .andExpect(status().isOk());
</#if>
<#if x.delete>
        created.push(path);
</#if>
    }
</#if>
</#list>
}
