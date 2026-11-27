package com.capstone.triplea.advertisement.image;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdImageStorageTest {

    private static final String NAME = "0123456789abcdef0123456789abcdef.jpg";   // 32자리 hex

    @TempDir
    Path dir;            // JUnit이 테스트마다 임시 폴더를 만들고 끝나면 지워줌

    private AdImageStorage storage;

    @BeforeEach
    void setUp() throws IOException {
        storage = new AdImageStorage(dir.toString());
        Files.write(dir.resolve(NAME), new byte[]{7, 8, 9});     // 정상 이미지 (내용은 아무 바이트)
        Files.write(dir.resolve("secret.txt"), new byte[]{1});   // 형식이 틀린 파일 (읽히면 안 됨)
    }

    @Test
    void 형식에_맞는_파일명만_찾는다() {
        assertThat(storage.find(NAME)).contains(dir.resolve(NAME));
        assertThat(storage.find("ffffffffffffffffffffffffffffffff.jpg")).isEmpty(); // 형식은 맞지만 파일 없음
        assertThat(storage.find("secret.txt")).isEmpty();   // 형식 위반
        assertThat(storage.find("../"+NAME)).isEmpty(); // 경로 조작
        assertThat(storage.find(NAME.toUpperCase())).isEmpty(); // 대문자 hex
        assertThat(storage.find(null)).isEmpty();
    }

    @Test
    void imageUrl로_이미지를_읽는다() {
        assertThat(storage.read(AdImageStorage.URL_PREFIX + NAME)).containsExactly(7,8,9);
    }

    @Test
    void 이_서버가_제공하는_URL이_아니면_예외() {
        assertThatThrownBy(() -> storage.read("https://example.com/" + NAME)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> storage.read(AdImageStorage.URL_PREFIX + "../secret.txt")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> storage.read(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 컨트롤러는_이미지를_jpeg로_내려주고_없으면_404() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdImageController(storage)).build();

        mvc.perform(get(AdImageStorage.URL_PREFIX + NAME))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(content().bytes(new byte[]{7, 8, 9}));
        mvc.perform(get(AdImageStorage.URL_PREFIX + "secret.txt"))
                .andExpect(status().isNotFound());
        mvc.perform(get(AdImageStorage.URL_PREFIX + "ffffffffffffffffffffffffffffffff.jpg"))
                .andExpect(status().isNotFound());
    }
}