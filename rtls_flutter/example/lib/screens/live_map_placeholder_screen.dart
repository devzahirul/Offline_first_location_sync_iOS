import 'package:flutter/material.dart';

/// Placeholder for Live Map (iOS has map with recorded + subscribed paths).
/// Plugin does not expose path data; full map would need plugin API or backend.
class LiveMapPlaceholderScreen extends StatelessWidget {
  const LiveMapPlaceholderScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: const Text('Live Map')),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(Icons.map_outlined, size: 64, color: theme.colorScheme.primary.withValues(alpha: 0.6)),
              const SizedBox(height: 16),
              Text(
                'Live Map',
                style: theme.textTheme.titleLarge,
              ),
              const SizedBox(height: 8),
              Text(
                'Device and watched user paths. Requires plugin or backend path API.',
                style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                textAlign: TextAlign.center,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
