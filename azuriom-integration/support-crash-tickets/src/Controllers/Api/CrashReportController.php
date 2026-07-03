<?php

namespace Azuriom\Plugin\Support\Controllers\Api;

use Azuriom\Http\Controllers\Controller;
use Azuriom\Models\User;
use Azuriom\Plugin\Support\Models\Category;
use Azuriom\Plugin\Support\Models\Ticket;
use Illuminate\Http\Request;
use Illuminate\Support\Str;

/**
 * Приймає краш-репорти від LaunchServer і створює тікети в Support-плагіні.
 *
 * Дедуплікація: хеш краша передається в subject у вигляді тега [#hash].
 * Якщо відкритий тікет з таким тегом вже існує — додається коментар
 * замість створення нового тікета.
 */
class CrashReportController extends Controller
{
    public function store(Request $request)
    {
        $token = setting('support.crash_token');

        if ($token === null || ! hash_equals($token, (string) $request->bearerToken())) {
            return response()->json(['message' => 'Unauthorized'], 401);
        }

        $data = $request->validate([
            'username' => ['required', 'string', 'max:100'],
            'subject' => ['required', 'string', 'max:150'],
            'content' => ['required', 'string', 'max:60000'],
            'hash' => ['nullable', 'string', 'max:64'],
        ]);

        $user = User::firstWhere('name', $data['username'])
            ?? User::find((int) setting('support.crash_fallback_user', 0));

        if ($user === null) {
            return response()->json(['message' => 'Unknown user and no fallback user configured'], 422);
        }

        // Дедуплікація: відкритий тікет з тим самим хешем краша
        if (! empty($data['hash'])) {
            $tag = '[#'.$data['hash'].']';

            $ticket = Ticket::open()
                ->where('subject', 'like', '%'.$tag.'%')
                ->latest()
                ->first();

            if ($ticket !== null) {
                $comment = $ticket->comments()->make(['content' => $data['content']]);
                $comment->author_id = $user->id;
                $comment->save();

                $ticket->touch();

                return response()->json([
                    'status' => 'comment_added',
                    'ticket_id' => $ticket->id,
                ]);
            }
        }

        $category = Category::find((int) setting('support.crash_category', 0))
            ?? Category::first();

        if ($category === null) {
            return response()->json(['message' => 'No support category available'], 422);
        }

        $ticket = new Ticket(['subject' => Str::limit($data['subject'], 150)]);
        $ticket->author_id = $user->id;
        $ticket->category_id = $category->id;
        $ticket->save();

        $comment = $ticket->comments()->make(['content' => $data['content']]);
        $comment->author_id = $user->id;
        $comment->save();

        if (($webhookUrl = setting('support.webhook')) !== null) {
            rescue(fn () => $ticket->createCreatedDiscordWebhook()->send($webhookUrl));
        }

        return response()->json([
            'status' => 'ticket_created',
            'ticket_id' => $ticket->id,
        ], 201);
    }
}
