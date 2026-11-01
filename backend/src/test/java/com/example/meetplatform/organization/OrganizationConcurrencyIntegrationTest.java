package com.example.meetplatform.organization;

import com.example.meetplatform.common.security.CurrentUser;
import com.example.meetplatform.organization.dto.OrganizationDtos.AddMemberRequest;
import com.example.meetplatform.organization.dto.OrganizationDtos.CreateOrganizationRequest;
import com.example.meetplatform.organization.entity.OrganizationMember.Role;
import com.example.meetplatform.organization.service.OrganizationService;
import com.example.meetplatform.support.AbstractIntegrationTest;
import com.example.meetplatform.support.TestUsers;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression: two admins demoting each other concurrently used to leave an organization with no admin. */
class OrganizationConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    OrganizationService organizations;

    @Autowired
    TestUsers users;

    @RepeatedTest(5)
    void mutualDemotionAlwaysLeavesAnAdmin() throws Exception {
        var a = users.create(mvc, "Admin A");
        var b = users.create(mvc, "Admin B");
        CurrentUser ua = new CurrentUser(a.id(), a.email(), false, UUID.randomUUID());
        CurrentUser ub = new CurrentUser(b.id(), b.email(), false, UUID.randomUUID());
        var org = organizations.create(ua, new CreateOrganizationRequest("Race", "race-" + UUID.randomUUID().toString().substring(0, 8)));
        organizations.addMember(ua, org.id(), new AddMemberRequest(b.email(), Role.ORGANIZATION_ADMIN));

        CountDownLatch go = new CountDownLatch(1);
        CompletableFuture<Void> f1 = CompletableFuture.runAsync(() -> attempt(go, () -> organizations.changeRole(ua, org.id(), b.id(), Role.MEMBER)));
        CompletableFuture<Void> f2 = CompletableFuture.runAsync(() -> attempt(go, () -> organizations.changeRole(ub, org.id(), a.id(), Role.MEMBER)));
        go.countDown();
        CompletableFuture.allOf(f1, f2).join();

        long admins = organizations.members(ua.userId().equals(a.id()) ? ua : ub, org.id()).stream()
                .filter(m -> m.role() == Role.ORGANIZATION_ADMIN).count();
        assertThat(admins).isGreaterThanOrEqualTo(1);
    }

    private static void attempt(CountDownLatch go, Runnable action) {
        try {
            go.await();
            action.run();
        } catch (Exception expected) {
            // the loser is rejected (last admin / no longer admin) - that is the correct outcome
        }
    }
}
