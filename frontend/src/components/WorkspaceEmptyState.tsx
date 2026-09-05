export default function WorkspaceEmptyState({ icon, text }: { icon: string; text: string }) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 py-12 text-center text-ink-faint">
      <span className="text-4xl" aria-hidden="true">{icon}</span>
      <p className="max-w-64 text-sm leading-relaxed">{text}</p>
    </div>
  );
}
