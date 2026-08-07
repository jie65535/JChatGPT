package top.jie65535.mirai.tools

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GithubAgentTest {
    @Test
    fun `common search and read commands are accepted`() {
        assertNull(
            GithubAgent.validateReadOnlyArgs(
                listOf("search", "code", "ChatCompletion language:kotlin", "--limit", "20", "--json", "repository,path,url")
            )
        )
        assertNull(
            GithubAgent.validateReadOnlyArgs(
                listOf("pr", "view", "123", "--repo", "owner/repo", "--json", "title,body,comments")
            )
        )
        assertNull(
            GithubAgent.validateReadOnlyArgs(
                listOf("api", "repos/owner/repo/pulls/123/files", "--jq", ".[].[filename,status]")
            )
        )
        assertNull(
            GithubAgent.validateReadOnlyArgs(
                listOf("api", "users/ZhengHe000", "--jq", "{login,name,followers,public_repos}")
            )
        )
        assertNull(
            GithubAgent.validateReadOnlyArgs(
                listOf("api", "orgs/github/repos?per_page=50", "--paginate", "--slurp")
            )
        )
        assertNull(GithubAgent.validateReadOnlyArgs(listOf("repo", "list", "github", "--limit", "20")))
        assertNull(GithubAgent.validateReadOnlyArgs(listOf("run", "view", "123", "--repo", "owner/repo")))
        assertNull(GithubAgent.validateReadOnlyArgs(listOf("workflow", "list", "--repo", "owner/repo")))
    }

    @Test
    fun `write and local state commands are rejected`() {
        assertNotNull(GithubAgent.validateReadOnlyArgs(listOf("issue", "create", "--title", "test")))
        assertNotNull(GithubAgent.validateReadOnlyArgs(listOf("pr", "merge", "123")))
        assertNotNull(GithubAgent.validateReadOnlyArgs(listOf("repo", "clone", "owner/repo")))
        assertNotNull(GithubAgent.validateReadOnlyArgs(listOf("auth", "token")))
        assertNotNull(GithubAgent.validateReadOnlyArgs(listOf("extension", "install", "owner/extension")))
    }

    @Test
    fun `api remains read only and limited to relative github rest endpoints`() {
        assertNotNull(GithubAgent.validateReadOnlyArgs(listOf("api", "graphql")))
        assertNotNull(GithubAgent.validateReadOnlyArgs(listOf("api", "graphql?query=viewer")))
        assertNotNull(GithubAgent.validateReadOnlyArgs(listOf("api", "https://example.com/private")))
        assertNotNull(GithubAgent.validateReadOnlyArgs(listOf("api", "//example.com/private")))
        assertNotNull(GithubAgent.validateReadOnlyArgs(listOf("api", "/repos/owner/repo")))
        assertNotNull(GithubAgent.validateReadOnlyArgs(listOf("api", "repos/../user")))
        assertNull(GithubAgent.validateReadOnlyArgs(listOf("api", "search/issues?q=url:https://example.com")))
        assertNotNull(
            GithubAgent.validateReadOnlyArgs(
                listOf("api", "repos/owner/repo/issues", "--method", "POST")
            )
        )
        assertNotNull(GithubAgent.validateReadOnlyArgs(listOf("api", "repos/owner/repo/issues", "-f", "state=open")))
    }

    @Test
    fun `result limits and unsafe global flags are rejected`() {
        assertNotNull(
            GithubAgent.validateReadOnlyArgs(
                listOf("search", "issues", "bug", "--limit", "51")
            )
        )
        assertNotNull(
            GithubAgent.validateReadOnlyArgs(
                listOf("repo", "view", "owner/repo", "--web")
            )
        )
        assertNotNull(
            GithubAgent.validateReadOnlyArgs(
                listOf("search", "repos", "kotlin", "--hostname=example.com")
            )
        )
    }
}
