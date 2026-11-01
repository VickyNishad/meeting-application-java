import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { CalendarPlus, History, Plus } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/overlays';
import { EmptyState, ErrorState, ListSkeleton, PageHeader, Pagination } from '@/components/common';
import { MeetingCard } from '@/components/meetings';
import { meetingsApi } from '@/lib/api/endpoints';

type Scope = 'upcoming' | 'past';

function MeetingList({ scope }: { scope: Scope }) {
  const [page, setPage] = useState(0);
  const q = useQuery({
    queryKey: ['meetings', scope, page],
    queryFn: () => meetingsApi.list(scope, page, 20),
    placeholderData: keepPreviousData,
  });
  if (q.isPending) return <ListSkeleton rows={4} />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  if (q.data.items.length === 0) {
    return scope === 'upcoming' ? (
      <EmptyState
        icon={CalendarPlus}
        title="No upcoming meetings"
        description="Scheduled meetings you host or are invited to appear here."
        action={
          <Button asChild>
            <Link to="/meetings/new">Schedule a meeting</Link>
          </Button>
        }
      />
    ) : (
      <EmptyState icon={History} title="No past meetings" description="Meetings you hosted or attended will appear here." />
    );
  }
  return (
    <>
      <div className="grid gap-3" aria-busy={q.isFetching}>
        {q.data.items.map((m) => (
          <MeetingCard key={m.id} meeting={m} />
        ))}
      </div>
      <Pagination page={q.data.page} totalPages={q.data.totalPages} onPageChange={setPage} />
    </>
  );
}

export default function MeetingsListPage() {
  const [params, setParams] = useSearchParams();
  const tab: Scope = params.get('tab') === 'past' ? 'past' : 'upcoming';
  return (
    <div>
      <PageHeader
        title="Meetings"
        description="Your upcoming meetings and meeting history."
        actions={
          <Button asChild>
            <Link to="/meetings/new">
              <Plus aria-hidden="true" /> New meeting
            </Link>
          </Button>
        }
      />
      <Tabs value={tab} onValueChange={(v) => setParams({ tab: v }, { replace: true })}>
        <TabsList>
          <TabsTrigger value="upcoming">Upcoming</TabsTrigger>
          <TabsTrigger value="past">Past</TabsTrigger>
        </TabsList>
        <TabsContent value="upcoming">
          <MeetingList scope="upcoming" />
        </TabsContent>
        <TabsContent value="past">
          <MeetingList scope="past" />
        </TabsContent>
      </Tabs>
    </div>
  );
}
