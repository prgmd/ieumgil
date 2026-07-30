package com.ssafy.ieumgil.global.exception;

import com.ssafy.ieumgil.global.security.jwt.JwtProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RestController
class ProbeController {
    @GetMapping("/__probe")
    String probe(@RequestParam double value) {
        return "ok:" + value;
    }
}

@WebMvcTest(controllers = ProbeController.class)
@AutoConfigureMockMvc(addFilters = false)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtProvider jwtProvider;

    @Test
    void missingRequiredParameterReturns400NotInternalServerError() throws Exception {
        mockMvc.perform(get("/__probe"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void wrongTypeParameterReturns400NotInternalServerError() throws Exception {
        mockMvc.perform(get("/__probe").param("value", "not-a-number"))
                .andExpect(status().isBadRequest());
    }
}
