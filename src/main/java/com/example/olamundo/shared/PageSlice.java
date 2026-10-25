package com.example.olamundo.shared;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record PageSlice<T>(List<T> content, int page, int size, boolean hasNext) {
	public static void validate(int page, int size) {
		if (page < 0 || size < 1 || size > 100) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use page >= 0 e size entre 1 e 100.");
		}
	}

	public static <T> PageSlice<T> from(List<T> results, int page, int size) {
		return new PageSlice<>(List.copyOf(results.subList(0, Math.min(results.size(), size))),
				page, size, results.size() > size);
	}
}
