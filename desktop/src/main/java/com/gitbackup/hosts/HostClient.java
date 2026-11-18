package com.gitbackup.hosts;

import com.gitbackup.model.RepoInfo;
import java.io.IOException;
import java.util.List;
import org.eclipse.jgit.transport.CredentialsProvider;

/** One signed-in account on GitHub, GitLab or Bitbucket. */
public interface HostClient {
    /** {@link RepoInfo#GITHUB}, {@link RepoInfo#GITLAB} or {@link RepoInfo#BITBUCKET}. */
    String host();

    /** The account name the sign-in belongs to. */
    String login() throws IOException;

    /** The repositories to offer, sorted by name. */
    List<RepoInfo> listRepos() throws IOException;

    /** What git needs to download this host's private repositories. */
    CredentialsProvider credentials();
}
