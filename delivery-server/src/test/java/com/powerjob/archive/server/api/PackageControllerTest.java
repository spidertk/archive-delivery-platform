package com.powerjob.archive.server.api;

import com.powerjob.archive.core.service.ResumeConflictException;
import com.powerjob.archive.core.service.ResumeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PackageController.class)
class PackageControllerTest {
    @Autowired MockMvc mvc;
    @MockBean ResumeService resumes;

    @Test
    void retriesOnlyCurrentFenceWithReason() throws Exception {
        mvc.perform(post("/api/v1/packages/101/retry").contentType("application/json")
                        .content("{\"expectedFence\":7,\"reason\":\"FTP network recovered\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.runStatus").value("READY"));
        verify(resumes).retry(101, 7, "FTP network recovered");
    }

    @Test
    void staleFenceIsConflictAndBlankReasonIsBadRequest() throws Exception {
        doThrow(new ResumeConflictException("stale")).when(resumes).retry(101, 7, "stale");
        mvc.perform(post("/api/v1/packages/101/retry").contentType("application/json")
                        .content("{\"expectedFence\":7,\"reason\":\"stale\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/packages/101/retry").contentType("application/json")
                        .content("{\"expectedFence\":7,\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
    }
}
