package com.capstone.triplea.advertisement.dto;

import com.capstone.triplea.advertisement.dto.AdGenerationDto.Vote;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdGenerationDtoTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void 형식이_다른_원소도_빼지_않고_자리를_유지한다() throws Exception {
        assertThat(AdGenerationDto.toVotes(json.readTree(
                "[{\"isAccepted\":true,\"reason\":\"ok\"},{\"reason\":\"판정 없음\"},{\"isAccepted\":\"yes\",\"reason\":3},\"문자열\"]")))
                .containsExactly(
                        new Vote(true, "ok"), new Vote(null, "판정 없음"), new Vote(null, null), new Vote(null, null));
    }

    @Test
    void 배열이_아니면_빈_목록이다() throws Exception {
        assertThat(AdGenerationDto.toVotes(null)).isEmpty();
        assertThat(AdGenerationDto.toVotes(json.readTree("null"))).isEmpty();
        assertThat(AdGenerationDto.toVotes(json.readTree("{\"isAccepted\":true}"))).isEmpty();
        assertThat(AdGenerationDto.toVotes(json.readTree("[]"))).isEmpty();
    }

    @Test
    void 판정은_isAccepted라는_이름으로만_내려간다() throws Exception {
        assertThat(json.writeValueAsString(new Vote(true, "ok"))).isEqualTo("{\"isAccepted\":true,\"reason\":\"ok\"}");
    }
}
