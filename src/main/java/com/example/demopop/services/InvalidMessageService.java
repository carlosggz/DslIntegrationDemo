package com.example.demopop.services;

import com.example.demopop.models.MailInfoDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class InvalidMessageService {

    public void process(MailInfoDto dto) {
        log.info("Processing invalid message: {}", dto);
    }
}
