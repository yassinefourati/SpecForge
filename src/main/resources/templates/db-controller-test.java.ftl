package [=pkg];
<#assign e = entity.db>
<#function sample t><#return {"Long": "1", "Integer": "1", "Short": "1", "UUID": "00000000-0000-0000-0000-000000000001", "LocalDate": "2026-01-01", "Boolean": "true"}[t]!"a"></#function>
<#assign itemPath><#if e.id.composite()><#list e.id.keyFields as f>/[=sample(f.javaType)]</#list><#else>/[=sample(e.id.field.javaType)]</#if></#assign>
<#assign required = false>
<#list e.requestComponents() as f><#if f.validations?seq_contains("@NotNull")><#assign required = true></#if></#list>

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import [=sharedModelPackage].PageResponse;
import [=servicePackage].[=e.serviceName];
import [=exceptionPackage].ResourceNotFoundException;
import [=checks.autoConfigureMockMvcImport];
import [=checks.webMvcTestImport];
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The web layer of [=e.controllerName]: status codes, the page shape and validation, with the service mocked
 * (filters off: security is the application's, see SecurityConfig).
 *
 * [=generatedNote]
 */
@WebMvcTest([=e.controllerName].class)
@AutoConfigureMockMvc(addFilters = false)
class [=e.controllerName]Test {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    [=e.serviceName] service;
<#if e.op("list")>

    @Test
    void listAnswersWithAPage() throws Exception {
        given(service.list(anyMap(), any())).willReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));
        mvc.perform(get("/[=e.path]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }
</#if>
<#if e.op("read")>

    @Test
    void anUnknownKeyIsNotFound() throws Exception {
        given(service.read(any())).willThrow(new ResourceNotFoundException("not found"));
        mvc.perform(get("/[=e.path][=itemPath]")).andExpect(status().isNotFound());
    }
</#if>
<#if e.op("create") && options.validation && required>

    @Test
    void anIncompleteBodyIsRejected() throws Exception {
        mvc.perform(post("/[=e.path]").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
</#if>
<#if e.op("delete")>

    @Test
    void deleteAnswersNoContent() throws Exception {
        mvc.perform(delete("/[=e.path][=itemPath]")).andExpect(status().isNoContent());
    }
</#if>
}
