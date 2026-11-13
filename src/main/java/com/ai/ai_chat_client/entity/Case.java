package com.ai.ai_chat_client.entity;

import java.util.List;

record Case(String q, String expectSource, List<String> ansKeys) {}
