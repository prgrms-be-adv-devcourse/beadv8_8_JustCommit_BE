package com.justcommit.backend.product.application;

import java.util.List;

public interface ImageDeleter {
    void delete(List<String> keys);
}
