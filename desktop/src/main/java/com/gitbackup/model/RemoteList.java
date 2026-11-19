package com.gitbackup.model;

import java.util.List;

/**
 * A list kept by the host itself, such as a GitHub star list.
 *
 * @param name the list's name as the host shows it
 * @param url  the list's page on the host
 * @param keys the repository keys in the list, in the host's order
 */
public record RemoteList(String name, String url, List<String> keys) {
    public RemoteList {
        keys = List.copyOf(keys);
    }
}
