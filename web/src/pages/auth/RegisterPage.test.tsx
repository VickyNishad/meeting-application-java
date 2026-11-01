import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { authApi } from '@/lib/api/endpoints';
import RegisterPage from './RegisterPage';

function renderPage() {
  const qc = new QueryClient();
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <RegisterPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

// zod 4 migration (Q-10): the custom messages must still reach the form fields through @hookform/resolvers.
describe('RegisterPage validation messages', () => {
  afterEach(() => vi.restoreAllMocks());

  it('shows the custom messages for empty / invalid input and does not submit', async () => {
    const register = vi.spyOn(authApi, 'register');
    renderPage();
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));
    expect(await screen.findByText('Name is required')).toBeInTheDocument();
    expect(screen.getByText('Enter a valid email address')).toBeInTheDocument();
    expect(screen.getByText('Use at least 12 characters')).toBeInTheDocument();
    expect(register).not.toHaveBeenCalled();
  });

  it('reports mismatched passwords on the confirm field (object-level refine with path)', async () => {
    const register = vi.spyOn(authApi, 'register');
    renderPage();
    await userEvent.type(screen.getByLabelText('Full name'), 'Ada');
    await userEvent.type(screen.getByLabelText('Email'), 'ada@example.com');
    await userEvent.type(screen.getByLabelText('Password'), 'correct horse battery');
    await userEvent.type(screen.getByLabelText('Confirm password'), 'correct horse batterx');
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));
    expect(await screen.findByText('Passwords do not match')).toBeInTheDocument();
    expect(register).not.toHaveBeenCalled();
  });

  it('submits trimmed values once valid', async () => {
    const register = vi.spyOn(authApi, 'register').mockResolvedValue({ userId: 'u1', emailVerificationRequired: true });
    renderPage();
    await userEvent.type(screen.getByLabelText('Full name'), '  Ada  ');
    await userEvent.type(screen.getByLabelText('Email'), '  ada@example.com ');
    await userEvent.type(screen.getByLabelText('Password'), 'correct horse battery');
    await userEvent.type(screen.getByLabelText('Confirm password'), 'correct horse battery');
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));
    expect(await screen.findByText(/check your inbox/i)).toBeInTheDocument();
    expect(register).toHaveBeenCalledWith(expect.objectContaining({ displayName: 'Ada', email: 'ada@example.com', password: 'correct horse battery' }));
  });
});
