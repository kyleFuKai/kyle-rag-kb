package com.kyle.rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
/**
 *
 *
 * @author kyle
 * @version 1.0
 * @date 2026/10/27 11:22
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MinioStorageService {

    public byte[] download(String objectPath) {
        throw new UnsupportedOperationException("占个位,稍后实现");
    }

    public void delete(String objectPath) {
        throw new UnsupportedOperationException("占个位,稍后实现");
    }
}
